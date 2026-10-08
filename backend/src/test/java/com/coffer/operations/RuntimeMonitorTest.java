package com.coffer.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeMonitorTest {
    @TempDir Path directory;
    OperationsDatabase db;
    RuntimeProperties properties;
    StorageProbe storage;
    RedisConnectionFactory redis;
    RedisConnection connection;
    RuntimeMonitor monitor;
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    static final String MARKER = "R26_PRIVATE_BODY_FILE_NAME_KEY_ENDPOINT";
    @BeforeEach void setup() throws Exception {
        db = new OperationsDatabase(); properties = new RuntimeProperties(); properties.setStorageVolume(directory.toString());
        Path receipt = directory.resolve("receipt.json");
        Files.writeString(receipt, receipt(Instant.now())); properties.setBackupReceipt(receipt.toString());
        storage = mock(StorageProbe.class); when(storage.check()).thenReturn(component("UP"));
        redis = mock(RedisConnectionFactory.class); connection = mock(RedisConnection.class);
        when(redis.getConnection()).thenReturn(connection); when(connection.ping()).thenReturn("PONG");
        var beans = new StaticListableBeanFactory(); beans.addBean("redis", redis);
        monitor = new RuntimeMonitor(properties, storage, db.dataSource, beans.getBeanProvider(RedisConnectionFactory.class), mapper, new MockEnvironment(), "");
    }
    @AfterEach void close() { monitor.close(); }
    static RuntimeMonitor.Component component(String status) { return new RuntimeMonitor.Component("storage", status, status.equals("UP") ? "OK" : "STORAGE_UNAVAILABLE", status.equals("UP") ? "NONE" : "CHECK_STORAGE", null, null); }
    static String receipt(Instant completed) { return "{\"completedAt\":\"" + completed + "\",\"verified\":true,\"sha256\":\"" + "a".repeat(64) + "\",\"sizeBytes\":123}"; }
    RuntimeMonitor.Component named(String name) { return monitor.snapshot().components().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow(); }
    @Test void failuresRecoverInReadinessAlertsAndHistoryWithoutContent() throws Exception {
        monitor.sample(); assertThat(monitor.snapshot().readiness()).isEqualTo("UP");
        when(storage.check()).thenThrow(new IllegalStateException(MARKER)); when(connection.ping()).thenThrow(new IllegalStateException(MARKER));
        monitor.sample(); assertThat(monitor.snapshot().readiness()).isEqualTo("DOWN");
        assertThat(monitor.snapshot().alerts()).extracting(RuntimeMonitor.Alert::action).contains("CHECK_STORAGE", "CHECK_REDIS");
        assertThat(new RuntimeReadinessIndicator(monitor).health().getStatus().getCode()).isEqualTo("DOWN");
        assertThat(new com.coffer.controller.HealthController(monitor).health().getStatusCode().value()).isEqualTo(503);
        assertThat(mapper.writeValueAsString(monitor.snapshot()) + mapper.writeValueAsString(monitor.history())).doesNotContain(MARKER, directory.toString());
        doReturn(component("UP")).when(storage).check(); doReturn("PONG").when(connection).ping(); monitor.sample();
        assertThat(monitor.snapshot().readiness()).isEqualTo("UP");
        assertThat(monitor.history()).isNotEmpty().allMatch(event -> event.resolvedAt() != null);
    }
    @Test void databaseFailureAndUnavailableHistoryAreNotReportedAsZeroOrSuccess() {
        db.jdbc.execute("DROP TABLE async_task"); monitor.sample();
        assertThat(monitor.snapshot().readiness()).isEqualTo("DOWN");
        assertThat(monitor.snapshot().queues()).isEmpty(); assertThat(named("database").status()).isEqualTo("DOWN");
        var registry = new SimpleMeterRegistry();
        try {
            new RuntimeMetrics(monitor, registry); assertThat(registry.find("coffer.runtime.queue").tag("kind", "pendingTasks").gauge().value()).isNaN();
        } finally { registry.close(); }
        db.jdbc.execute("DROP TABLE runtime_alert"); monitor.sample();
        assertThat(monitor.snapshot().alerts()).extracting(RuntimeMonitor.Alert::code).contains("ALERT_HISTORY_UNAVAILABLE");
        assertThatThrownBy(monitor::history).isInstanceOf(RuntimeStatusUnavailableException.class);
    }
    @Test void samplesAreBoundedAndStaleSamplesDiscardOldHealthyValues() throws Exception {
        when(storage.check()).thenAnswer(i -> { Thread.sleep(10000); return component("UP"); });
        Instant before = Instant.now(); monitor.sample();
        assertThat(Duration.between(before, Instant.now()).toMillis()).isLessThan(6000);
        assertThat(named("storage").status()).isEqualTo("DOWN");
        var current = monitor.snapshot(); ReflectionTestUtils.setField(monitor, "latest", new RuntimeMonitor.Snapshot(Instant.now().minusSeconds(120), "UP", current.components(), current.queues(), current.connections(), List.of()));
        assertThat(monitor.snapshot().readiness()).isEqualTo("OUT_OF_SERVICE");
        assertThat(monitor.snapshot().components()).allMatch(c -> c.status().equals("UNKNOWN") && c.totalBytes() == null);
        assertThat(monitor.snapshot().queues()).isEmpty();
    }
    @Test void capacityAndBackupHaveDistinctUnknownInvalidStaleAndLowStates() throws Exception {
        properties.setMinimumFreeBytes(Long.MAX_VALUE); monitor.sample();
        assertThat(named("capacity").reason()).isEqualTo("DISK_LOW"); assertThat(monitor.snapshot().readiness()).isEqualTo("DOWN");
        properties.setMinimumFreeBytes(1); Files.writeString(Path.of(properties.getBackupReceipt()), receipt(Instant.now().minus(Duration.ofDays(4)))); monitor.sample();
        assertThat(named("backup").reason()).isEqualTo("BACKUP_STALE"); assertThat(monitor.snapshot().readiness()).isEqualTo("UP");
        Files.writeString(Path.of(properties.getBackupReceipt()), MARKER); monitor.sample(); assertThat(named("backup").reason()).isEqualTo("RECEIPT_INVALID");
        properties.setStorageVolume(""); properties.setBackupReceipt(""); monitor.sample();
        assertThat(named("backup").status()).isEqualTo("UNKNOWN"); assertThat(named("capacity").status()).isEqualTo("UNKNOWN");
        assertThat(monitor.snapshot().alerts()).hasSize(2);
        properties.setStorageVolume(directory.resolve("missing").toString()); monitor.sample(); assertThat(monitor.snapshot().readiness()).isEqualTo("DOWN");
    }
    @Test void numericQueuesIncludeDisabledOwnersAndRunningCompensationWithoutNames() throws Exception {
        long owner = db.owner(false);
        db.jdbc.update("INSERT INTO async_task(task_id,file_name,status,created_at,owner_id) VALUES(? ,?,'PENDING',?,?)", UUID.randomUUID().toString(), MARKER, java.sql.Timestamp.from(Instant.now().minusSeconds(3600)), owner);
        db.jdbc.update("INSERT INTO governance_compensation_task(task_key,batch_id,item_id,action,status,created_at,updated_at,owner_id) VALUES(?,'b',1,'COPY','RUNNING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)", MARKER, owner);
        monitor.sample(); assertThat(monitor.snapshot().queues()).containsEntry("pendingTasks", 1L).containsEntry("pendingCompensations", 1L);
        assertThat(monitor.snapshot().alerts()).extracting(RuntimeMonitor.Alert::code).contains("TASK_BACKLOG");
        assertThat(mapper.writeValueAsString(monitor.snapshot())).doesNotContain(MARKER);
        assertThat(monitor.publicStatus().queues()).isEmpty(); assertThat(monitor.publicStatus().alerts()).isEmpty();
        assertThat(monitor.publicStatus().components()).allMatch(c -> c.totalBytes() == null && c.freeBytes() == null);
        var registry = new SimpleMeterRegistry();
        try {
            new RuntimeMetrics(monitor, registry);
            assertThat(registry.getMeters()).allMatch(m -> m.getId().getTags().stream().allMatch(tag -> java.util.Set.of("kind", "state").contains(tag.getKey()) && !tag.getValue().contains(MARKER)));
        } finally { registry.close(); }
    }
    @Test void desktopProbesStorageWithActualWriteReadDeleteAndDoesNotRequireRedis() {
        monitor.close(); var environment = new MockEnvironment(); environment.setActiveProfiles("desktop");
        var beans = new StaticListableBeanFactory();
        monitor = new RuntimeMonitor(properties, new LocalStorageProbe(directory.toString()), db.dataSource, beans.getBeanProvider(RedisConnectionFactory.class), mapper, environment, directory.toString());
        monitor.sample(); assertThat(monitor.snapshot().readiness()).isEqualTo("UP"); assertThat(named("redis").status()).isEqualTo("NOT_REQUIRED");
        assertThat(directory.toFile().list()).noneMatch(name -> name.startsWith(".coffer-health-"));
        assertThat(new LocalStorageProbe(directory.resolve("missing").toString()).check().status()).isEqualTo("DOWN");
    }
    @Test void actualConnectionPoolAndManualBacklogsExposeOnlyTotals() {
        long owner = db.owner(false);
        db.jdbc.update("INSERT INTO file_write_intent(id,owner_id,task_id,kind,object_key,file_name,declared_size,status,attempts,created_at,updated_at) VALUES('i',?,'t','UPLOAD','private/key',?,1,'MANUAL_REVIEW',2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", owner, MARKER);
        monitor.close(); var config = new com.zaxxer.hikari.HikariConfig(); config.setDataSource(db.dataSource); config.setMaximumPoolSize(2); config.setMinimumIdle(0);
        var pool = new com.zaxxer.hikari.HikariDataSource(config);
        try {
            var beans = new StaticListableBeanFactory(); beans.addBean("redis", redis);
            monitor = new RuntimeMonitor(properties, storage, pool, beans.getBeanProvider(RedisConnectionFactory.class), mapper, new MockEnvironment(), "");
            monitor.sample(); assertThat(monitor.snapshot().connections()).containsEntry("limit", 2L).containsKeys("active", "idle", "waiting");
            assertThat(monitor.snapshot().queues()).containsEntry("manualReview", 1L);
            assertThat(monitor.snapshot().alerts()).extracting(RuntimeMonitor.Alert::code).contains("MANUAL_REVIEW_REQUIRED");
            assertThat(monitor.publicStatus().connections()).isEmpty();
        } finally { monitor.close(); pool.close(); }
    }
    @Test void blockedBusinessScheduleDoesNotStarveRuntimeSampling() throws Exception {
        var configuration = new RuntimeSchedulingConfiguration();
        var business = configuration.taskScheduler(); var health = configuration.runtimeMonitorScheduler();
        business.initialize(); health.initialize();
        var release = new java.util.concurrent.CountDownLatch(1); var sampled = new java.util.concurrent.CountDownLatch(1);
        try {
            business.execute(() -> { try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } });
            health.execute(() -> { monitor.sample(); sampled.countDown(); });
            assertThat(sampled.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(monitor.snapshot().readiness()).isEqualTo("UP");
        } finally { release.countDown(); business.shutdown(); health.shutdown(); }
    }
}
