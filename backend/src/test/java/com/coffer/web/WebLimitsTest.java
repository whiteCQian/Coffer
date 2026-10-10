package com.coffer.web;
import com.coffer.auth.service.TenantContext;
import com.coffer.auth.service.PersistentRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class WebLimitsTest {
    @TempDir Path directory;
    JdbcTemplate jdbc; WebLimitProperties properties; WebLimits limits;
    DataSourceTransactionManager transactions; DriverManagerDataSource source;
    @BeforeEach void setup() throws Exception {
        source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
        transactions=new DataSourceTransactionManager(source); jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY)"); jdbc.update("INSERT INTO app_user VALUES(1),(2)");
        jdbc.execute("CREATE TABLE async_task(task_id VARCHAR(36),owner_id BIGINT,status VARCHAR(16))");
        jdbc.execute("CREATE TABLE file_write_intent(task_id VARCHAR(36),owner_id BIGINT,status VARCHAR(24))");
        try(var connection=source.getConnection()) { ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/h2/V43__web_security_capacity.sql")); }
        jdbc.update("UPDATE web_quota_lock SET initialized=TRUE");
        properties=new WebLimitProperties(); properties.setAccountBytes(100); properties.setTotalBytes(1000); properties.setAccountObjects(2);
        properties.setReserveFreeBytes(100); properties.setReserveFreePercent(5); properties.setCapacityReceipt(directory.resolve("capacity.json").toString());
        sample(Instant.now(),1000,900);
        limits=proxy(new WebLimits(jdbc,properties,new MinioVolumeCapacity(properties,new ObjectMapper())));
        TenantContext.set(1L);
    }
    <T> T proxy(T target) {
        var factory=new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        return (T)factory.getProxy();
    }
    void sample(Instant time,long total,long free) throws Exception { Files.writeString(Path.of(properties.getCapacityReceipt()),"{\"checkedAt\":\""+time+"\",\"totalBytes\":"+total+",\"freeBytes\":"+free+"}"); }
    @AfterEach void clear(){TenantContext.clear();}
    @Test void reservationSurvivesUncertainIoAndRestartAndOnlyVerifiedDeletionReleasesBytes() {
        limits.reserve("users/1/a",60); limits.reserve("users/1/a",60);
        assertThat(limits.usage().usedBytes()).isEqualTo(60);
        limits=proxy(new WebLimits(jdbc,properties,new MinioVolumeCapacity(properties,new ObjectMapper())));
        assertThatThrownBy(()->limits.reserve("users/1/b",41)).isInstanceOf(WebLimitException.class).hasMessageContaining("账号存储配额");
        limits.stored("users/1/a");
        assertThatThrownBy(()->limits.reserve("users/1/a",60)).isInstanceOf(WebLimitException.class);
        limits.deleted("users/1/a"); limits.reserve("users/1/b",100);
        assertThat(limits.usage().usedBytes()).isEqualTo(100);
    }
    @Test void concurrentReservationsCannotOversubscribeAccountAndOwnersRemainIsolated() throws Exception {
        var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            var attempts=new ArrayList<Future<Boolean>>();
            for(int n=0;n<2;n++){ final int index=n; attempts.add(pool.submit(()->{
                TenantContext.set(1L); try { start.await(); limits.reserve("users/1/"+index,60); return true; }
                catch(WebLimitException quota){assertThat(quota.status()).isEqualTo(413);return false;}
                finally {TenantContext.clear();}
            }));}
            start.countDown(); var results=new ArrayList<Boolean>(); for(var attempt:attempts)results.add(attempt.get(10,TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder(true,false); assertThat(limits.usage().usedBytes()).isEqualTo(60);
            TenantContext.set(2L); limits.reserve("users/2/a",60); assertThat(limits.usage().usedBytes()).isEqualTo(60);
            assertThatThrownBy(()->limits.reserve("users/1/x",1)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        } finally {pool.shutdownNow();}
    }
    @Test void objectCountTotalQuotaAndStaleOrLowCapacityRejectWithExplainableStatus() throws Exception {
        limits.reserve("users/1/a",1); limits.reserve("users/1/b",1);
        assertThatThrownBy(()->limits.reserve("users/1/c",1)).hasMessageContaining("账号存储配额");
        TenantContext.set(2L); properties.setTotalBytes(2);
        assertThatThrownBy(()->limits.reserve("users/2/a",1)).isInstanceOf(WebLimitException.class).hasMessageContaining("服务存储配额");
        properties.setTotalBytes(1000); sample(Instant.now().minusSeconds(40),1000,900);
        assertThatThrownBy(()->limits.reserve("users/2/a",1)).hasMessageContaining("容量暂时无法核实");
        sample(Instant.now(),1000,100);
        assertThatThrownBy(()->limits.reserve("users/2/a",1)).hasMessageContaining("持久卷剩余容量不足");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM web_storage_allocation WHERE owner_id=2",Long.class)).isZero();
    }
    @Test void taskAdmissionAndClaimAreOwnerScopedAndDurable() {
        properties.setPendingTasks(2); properties.setConcurrentTasks(1);
        jdbc.update("INSERT INTO async_task VALUES('a',1,'PROCESSING'),('b',1,'PENDING')");
        var tx=new TransactionTemplate(transactions);
        assertThatThrownBy(()->tx.execute(s->{limits.admitTask("c");return null;})).hasMessageContaining("待处理任务");
        Boolean first=tx.execute(s->limits.canClaimTask()); assertThat(first).isFalse();
        TenantContext.set(2L); Boolean second=tx.execute(s->limits.canClaimTask()); assertThat(second).isTrue();
        tx.execute(s->{limits.admitTask("c");return null;});
    }
    @Test void productionRateWindowsSurviveInstanceRecreationAndDoNotStoreCredentialsOrDiscardOtherWindows() {
        var limiter=proxy(new PersistentRateLimiter(jdbc));
        assertThat(limiter.acquire("login:private-ip",2,Duration.ofMinutes(15))).isTrue();
        limiter=proxy(new PersistentRateLimiter(jdbc));
        assertThat(limiter.acquire("login:private-ip",2,Duration.ofMinutes(15))).isTrue();
        assertThat(limiter.acquire("login:private-ip",2,Duration.ofMinutes(15))).isFalse();
        assertThat(limiter.acquire("reset:other",2,Duration.ofHours(1))).isTrue();
        jdbc.update("UPDATE auth_rate_bucket SET expires_at=0 WHERE key_hash=?",WebLimits.hash("login:private-ip"));
        assertThat(limiter.acquire("login:private-ip",2,Duration.ofMinutes(15))).isTrue();
        assertThat(jdbc.queryForList("SELECT key_hash FROM auth_rate_bucket",String.class)).allMatch(s->s.matches("[0-9a-f]{64}"));
    }
}
