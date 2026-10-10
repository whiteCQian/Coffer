package com.coffer.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Fixed, content-free probes. HTTP, health groups and alerts read the same bounded sample. */
@Service
public class RuntimeMonitor {
    public record Component(String name, String status, String reason, String action, Long totalBytes, Long freeBytes) { }
    public record Alert(String code, String severity, String action) { }
    public record Snapshot(Instant checkedAt, String readiness, List<Component> components,
                           Map<String, Long> queues, Map<String, Long> connections, List<Alert> alerts) { }
    private final RuntimeProperties properties;
    private final StorageProbe storage;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final ObjectProvider<RedisConnectionFactory> redis;
    private final ObjectMapper mapper;
    private final boolean desktop;
    private final boolean optionalRedis;
    private final String localRoot;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.coffer.web.MinioVolumeCapacity minioCapacity;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), r -> { var t = new Thread(r, "coffer-runtime-probe"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
    private volatile Snapshot latest = new Snapshot(Instant.EPOCH, "OUT_OF_SERVICE", List.of(), Map.of(), Map.of(), List.of());

    public RuntimeMonitor(RuntimeProperties properties, StorageProbe storage, DataSource dataSource,
                          ObjectProvider<RedisConnectionFactory> redis, ObjectMapper mapper, Environment environment,
                          @Value("${coffer.storage.local.root:}") String localRoot) {
        this.properties = properties; this.storage = storage; this.redis = redis; this.mapper = mapper;
        this.desktop = environment.matchesProfiles("desktop"); this.localRoot = localRoot;
        this.optionalRedis = environment.matchesProfiles("prod");
        this.dataSource = dataSource; this.jdbc = new JdbcTemplate(dataSource); jdbc.setQueryTimeout(2);
    }

    @Scheduled(scheduler = "runtimeMonitorScheduler", initialDelay = 2000, fixedDelayString = "${coffer.operations.sample-ms:30000}")
    public synchronized void sample() {
        Map<String, Long> counts = bounded(() -> queues(), Map.of());
        Component database = new Component("database", counts.isEmpty() ? "DOWN" : "UP",
                counts.isEmpty() ? "DATABASE_UNAVAILABLE" : "OK", counts.isEmpty() ? "CHECK_DATABASE" : "NONE", null, null);
        Component objectStore = bounded(storage::check, new Component("storage", "DOWN", "PROBE_TIMEOUT", "CHECK_STORAGE", null, null));
        Component redisStatus = bounded(this::redisCheck, new Component("redis", optionalRedis ? "DEGRADED" : "DOWN", "PROBE_TIMEOUT", "CHECK_REDIS", null, null));
        Component capacity = bounded(this::capacity, new Component("capacity", "DOWN", "PROBE_TIMEOUT", "CHECK_CAPACITY", null, null));
        Component backup = bounded(this::backup, new Component("backup", "DOWN", "RECEIPT_INVALID", "CHECK_BACKUP", null, null));
        List<Component> components = List.of(database, objectStore, redisStatus, capacity, backup);
        List<Alert> alerts = new ArrayList<>();
        Map<String, Long> connections = connectionStats();
        if (connections.getOrDefault("waiting", 0L) > 0) alerts.add(new Alert("DATABASE_POOL_BUSY", "WARNING", "CHECK_DATABASE"));
        for (Component component : components) if (!Set.of("UP", "NOT_REQUIRED").contains(component.status()))
            alerts.add(new Alert(component.name().toUpperCase(Locale.ROOT) + "_" + component.reason(),
                    component.status().equals("DOWN") ? "CRITICAL" : "WARNING", component.action()));
        if (counts.getOrDefault("manualReview", 0L) > 0) alerts.add(new Alert("MANUAL_REVIEW_REQUIRED", "WARNING", "OWNER_REVIEW"));
        if (counts.getOrDefault("oldestPendingMinutes", 0L) >= properties.getBacklogMaxAgeMinutes()
                || counts.entrySet().stream().filter(e -> e.getKey().startsWith("pending"))
                .mapToLong(Map.Entry::getValue).sum() >= properties.getBacklogWarningCount())
            alerts.add(new Alert("TASK_BACKLOG", "WARNING", "CHECK_WORKERS"));
        if (counts.getOrDefault("failedTasks", 0L) > 0) alerts.add(new Alert("FAILED_TASKS", "WARNING", "OWNER_RETRY"));
        if (counts.getOrDefault("failedVectorReindexes", 0L) > 0) alerts.add(new Alert("VECTOR_REINDEX_FAILED", "WARNING", "OWNER_RETRY"));
        boolean ready = components.stream().noneMatch(c -> c.status().equals("DOWN") && !c.name().equals("backup"));
        boolean saved = bounded(() -> { recordAlerts(alerts); return true; }, false);
        if (!saved) alerts.add(new Alert("ALERT_HISTORY_UNAVAILABLE", "WARNING", "CHECK_DATABASE"));
        latest = new Snapshot(Instant.now(), ready ? "UP" : "DOWN", components, Map.copyOf(counts), connections, List.copyOf(alerts));
    }

    public Snapshot snapshot() {
        Snapshot snapshot = latest;
        if (Duration.between(snapshot.checkedAt(), Instant.now()).getSeconds() > 90)
            return new Snapshot(snapshot.checkedAt(), "OUT_OF_SERVICE", snapshot.components().stream()
                    .map(c -> new Component(c.name(), "UNKNOWN", "MONITOR_STALE", "CHECK_WORKERS", null, null)).toList(), Map.of(), Map.of(),
                    List.of(new Alert("MONITOR_STALE", "CRITICAL", "CHECK_WORKERS")));
        return snapshot;
    }
    public Snapshot publicStatus() {
        Snapshot snapshot = snapshot();
        return new Snapshot(snapshot.checkedAt(), snapshot.readiness(), snapshot.components().stream()
                .filter(c -> Set.of("database", "storage", "redis", "capacity").contains(c.name()))
                .map(c -> new Component(c.name(), c.status(), c.reason(), c.action(), null, null)).toList(), Map.of(), Map.of(), List.of());
    }
    private Map<String, Long> connectionStats() {
        if (dataSource instanceof com.zaxxer.hikari.HikariDataSource pool && pool.getHikariPoolMXBean() != null) {
            var stats = pool.getHikariPoolMXBean();
            return Map.of("active", (long) stats.getActiveConnections(), "idle", (long) stats.getIdleConnections(),
                    "waiting", (long) stats.getThreadsAwaitingConnection(), "limit", (long) pool.getMaximumPoolSize());
        }
        return Map.of();
    }
    public record AlertEvent(String code, String severity, Instant firstSeen, Instant lastSeen, Instant resolvedAt) { }
    public List<AlertEvent> history() {
        List<AlertEvent> events = bounded(() -> jdbc.query("SELECT code, severity, first_seen, last_seen, resolved_at FROM runtime_alert ORDER BY id DESC LIMIT 100",
                (row, index) -> new AlertEvent(row.getString(1), row.getString(2), row.getTimestamp(3).toInstant(),
                        row.getTimestamp(4).toInstant(), row.getTimestamp(5) == null ? null : row.getTimestamp(5).toInstant())), null);
        if (events == null) throw new RuntimeStatusUnavailableException();
        return events;
    }
    private Map<String, Long> queues() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("pendingTasks", count("async_task", "status='PENDING'"));
        result.put("processingTasks", count("async_task", "status='PROCESSING'"));
        result.put("failedTasks", count("async_task", "status='FAILED'"));
        result.put("pendingCompensations", count("governance_compensation_task", "status IN ('PENDING','RUNNING','FAILED')"));
        result.put("pendingDeletions", count("storage_deletion_task", "status IN ('PENDING','RUNNING','FAILED') AND (retention_until IS NULL OR retention_until<=CURRENT_TIMESTAMP)"));
        result.put("pendingVectorCleanups", count("vector_cleanup_task", "status IN ('PENDING','RUNNING')"));
        result.put("pendingWrites", count("file_write_intent", "status IN ('PREPARED','OBJECT_WRITTEN','FAILED')"));
        result.put("pendingRenames", count("file_rename_intent", "status IN ('PREPARED','FAILED')"));
        result.put("pendingWorkSaves", count("work_save_intent", "status IN ('PREPARED','OBJECT_WRITTEN')"));
        result.put("failedVectorReindexes", count("vector_reindex_job", "status IN ('PARTIAL','FAILED')"));
        long manual = 0;
        for (String table : List.of("storage_deletion_task", "governance_compensation_task", "file_write_intent", "file_rename_intent", "work_save_intent", "inbox_import_record"))
            manual += count(table, "status IN ('MANUAL_REVIEW','CONFLICTED')");
        manual += count("archive_operation_item", "execution_status IN ('MANUAL_REVIEW','CONFLICTED') OR rollback_status='CONFLICTED'");
        result.put("manualReview", manual);
        Timestamp oldest = jdbc.queryForObject("SELECT MIN(created_at) FROM ("
                + "SELECT created_at FROM async_task WHERE owner_id IS NOT NULL AND status IN ('PENDING','PROCESSING') UNION ALL "
                + "SELECT created_at FROM governance_compensation_task WHERE owner_id IS NOT NULL AND status IN ('PENDING','RUNNING','FAILED') UNION ALL "
                + "SELECT created_at FROM file_write_intent WHERE owner_id IS NOT NULL AND status IN ('PREPARED','OBJECT_WRITTEN','FAILED') UNION ALL "
                + "SELECT created_at FROM file_rename_intent WHERE owner_id IS NOT NULL AND status IN ('PREPARED','FAILED') UNION ALL "
                + "SELECT created_at FROM work_save_intent WHERE owner_id IS NOT NULL AND status IN ('PREPARED','OBJECT_WRITTEN')) pending", Timestamp.class);
        result.put("oldestPendingMinutes", oldest == null ? 0L : Math.max(0, Duration.between(oldest.toInstant(), Instant.now()).toMinutes()));
        return result;
    }
    private long count(String table, String condition) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE owner_id IS NOT NULL AND (" + condition + ")", Long.class);
    }
    private Component redisCheck() {
        if (desktop) return new Component("redis", "NOT_REQUIRED", "OPTIONAL_DESKTOP", "NONE", null, null);
        try {
            var factory = redis.getIfAvailable();
            if (factory == null) throw new IllegalStateException();
            try (var connection = factory.getConnection()) {
                if (!"PONG".equals(connection.ping())) throw new IllegalStateException();
            }
            return new Component("redis", "UP", "OK", "NONE", null, null);
        } catch (Exception failure) { return new Component("redis", optionalRedis ? "DEGRADED" : "DOWN", "REDIS_UNAVAILABLE", "CHECK_REDIS", null, null); }
    }
    private Component capacity() throws Exception {
        if (optionalRedis && minioCapacity != null) {
            try {
                var sample = minioCapacity.sample();
                boolean low = sample.freeBytes() < properties.getMinimumFreeBytes()
                        || (double)sample.freeBytes() / sample.totalBytes() * 100 < properties.getMinimumFreePercent();
                return new Component("capacity", low ? "DEGRADED" : "UP", low ? "MINIO_DISK_LOW" : "OK", low ? "FREE_MINIO_CAPACITY" : "NONE", sample.totalBytes(), sample.freeBytes());
            } catch (com.coffer.web.WebLimitException unavailable) {
                return new Component("capacity", "DOWN", "MINIO_CAPACITY_STALE", "CHECK_MINIO_VOLUME_PROBE", null, null);
            }
        }
        String location = desktop ? localRoot : properties.getStorageVolume();
        if (location == null || location.isBlank()) return new Component("capacity", "UNKNOWN", "VOLUME_NOT_CONFIGURED", "CONFIGURE_CAPACITY", null, null);
        Path path = Path.of(location);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException();
        var store = Files.getFileStore(path);
        long total = store.getTotalSpace(), free = store.getUsableSpace();
        boolean low = total <= 0 || free < properties.getMinimumFreeBytes()
                || (double) free / total * 100 < properties.getMinimumFreePercent();
        return new Component("capacity", low ? "DOWN" : "UP", low ? "DISK_LOW" : "OK", low ? "FREE_CAPACITY" : "NONE", total, free);
    }
    private Component backup() throws Exception {
        String receipt = properties.getBackupReceipt();
        if (receipt == null || receipt.isBlank()) return new Component("backup", "UNKNOWN", "RECEIPT_NOT_CONFIGURED", "CONFIGURE_BACKUP", null, null);
        Path path = Path.of(receipt);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16384)
            return new Component("backup", "DOWN", "RECEIPT_INVALID", "CHECK_BACKUP", null, null);
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(16385); }
        if (bytes.length > 16384) return new Component("backup", "DOWN", "RECEIPT_INVALID", "CHECK_BACKUP", null, null);
        var node = mapper.readTree(bytes);
        Instant completed = Instant.parse(node.path("completedAt").asText());
        boolean verified = node.path("verified").asBoolean(false) && node.path("sha256").asText().matches("[0-9a-f]{64}")
                && node.path("sizeBytes").asLong(-1) > 0 && !completed.isAfter(Instant.now().plusSeconds(60));
        boolean fresh = verified && Duration.between(completed, Instant.now()).toHours() < properties.getBackupMaxAgeHours();
        return new Component("backup", fresh ? "UP" : "DOWN", !verified ? "RECEIPT_INVALID" : fresh ? "OK" : "BACKUP_STALE",
                fresh ? "NONE" : "RUN_VERIFIED_BACKUP", null, null);
    }
    private void recordAlerts(List<Alert> alerts) {
        Set<String> active = new HashSet<>();
        for (Alert alert : alerts) {
            active.add(alert.code());
            int updated = jdbc.update("UPDATE runtime_alert SET last_seen=CURRENT_TIMESTAMP WHERE code=? AND resolved_at IS NULL", alert.code());
            if (updated == 0) jdbc.update("INSERT INTO runtime_alert(code,severity,first_seen,last_seen) VALUES(?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", alert.code(), alert.severity());
        }
        for (String code : jdbc.queryForList("SELECT code FROM runtime_alert WHERE resolved_at IS NULL", String.class))
            if (!active.contains(code)) jdbc.update("UPDATE runtime_alert SET resolved_at=CURRENT_TIMESTAMP WHERE code=? AND resolved_at IS NULL", code);
    }
    private <T> T bounded(Callable<T> action, T failure) {
        Future<T> future = null;
        try { future = workers.submit(action); return future.get(2, TimeUnit.SECONDS); }
        catch (InterruptedException ignored) { if (future != null) future.cancel(true); Thread.currentThread().interrupt(); return failure; }
        catch (Exception ignored) { if (future != null) future.cancel(true); return failure; }
    }
    @PreDestroy public void close() { workers.shutdownNow(); }
}
