package com.coffer.deployment;

import com.coffer.CofferApplication;
import com.coffer.auth.domain.*;
import com.coffer.auth.infrastructure.AppUserRepository;
import com.coffer.auth.service.TenantContext;
import com.coffer.memory.*;
import com.coffer.privacy.*;
import com.coffer.service.ChatSessionService;
import dev.langchain4j.data.message.*;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** Production profile and server adapter; independent of the desktop lifecycle and its tests.
 * H2 isolates memory lifecycle here; MySQL/container evidence is the deployment acceptance suite. */
class WebProductionMemoryIntegrationTest {
    @TempDir Path directory;
    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(CofferApplication.class).run(
                "--spring.profiles.active=prod", "--server.port=0", "--management.server.port=0",
                "--spring.datasource.url=jdbc:h2:file:" + directory.resolve("web-db") + ";DB_CLOSE_ON_EXIT=FALSE",
                "--spring.datasource.driver-class-name=org.h2.Driver", "--MYSQL_USERNAME=sa", "--MYSQL_PASSWORD=",
                "--spring.flyway.locations=classpath:db/migration/h2", "--spring.flyway.baseline-on-migrate=false",
                "--COFFER_SECRET_KEY=isolated-production-test-key", "--COFFER_LOG_DIR=" + directory.resolve("logs"),
                "--minio.endpoint=http://127.0.0.1:1", "--minio.access-key=isolated-app", "--minio.secret-key=isolated-secret", "--minio.bucket-name=",
                "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=1", "--spring.data.redis.connect-timeout=100ms", "--spring.data.redis.timeout=100ms",
                "--coffer.embedding.enabled=false", "--coffer.hybrid.enabled=false", "--spring.main.banner-mode=off");
    }
    @AfterEach void clear() {
        TenantContext.clear();
        var logs = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        var appender = logs.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("FILE");
        if (appender instanceof ch.qos.logback.core.FileAppender<?> file) file.stop();
    }
    @Test void webMemorySurvivesRedisOutageAndRestartWithOwnerIsolationAndDurableErasure() {
        Long owner, other;
        String session;
        var frames = List.<ChatMessage>of(UserMessage.from("web-private"), AiMessage.from("ok"));
        try (var app = start()) {
            assertThat(app.getEnvironment().matchesProfiles("desktop")).isFalse();
            assertThat(app.containsBean("minioClient")).isTrue();
            assertThat(app.getBean(ChatMemoryStore.class)).isInstanceOf(DatabaseChatMemoryStore.class);
            assertThat(app.getBeansOfType(RedisChatMemoryStore.class)).isEmpty();
            assertThat(app.getBean(MemoryCleanup.class)).isInstanceOf(DatabaseMemoryCleanup.class);
            assertThatThrownBy(() -> app.getBean(org.springframework.data.redis.core.StringRedisTemplate.class).getConnectionFactory().getConnection().ping()).isInstanceOf(RuntimeException.class);
            var users = app.getBean(AppUserRepository.class);
            owner = users.saveAndFlush(new AppUser("web-a", "unused", AuthRole.USER)).getId();
            other = users.saveAndFlush(new AppUser("web-b", "unused", AuthRole.USER)).getId();
            TenantContext.set(owner);
            session = app.getBean(ChatSessionService.class).create();
            app.getBean(ChatMemoryStore.class).updateMessages(new OwnerMemoryId(owner, session), frames);
            assertThat(app.getBean(ChatMemoryStore.class).getMessages(new OwnerMemoryId(owner, session))).isEqualTo(frames);
            TenantContext.clear();
        }
        try (var app = start()) {
            TenantContext.set(other);
            assertThatThrownBy(() -> app.getBean(ChatMemoryStore.class).getMessages(new OwnerMemoryId(owner, session))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> app.getBean(ChatMemoryStore.class).getMessages("raw-redis-key")).isInstanceOf(RuntimeException.class);
            TenantContext.set(owner);
            assertThat(app.getBean(ChatMemoryStore.class).getMessages(new OwnerMemoryId(owner, session))).isEqualTo(frames);
            app.getBean(MemoryCleanup.class).delete(session);
            assertThat(app.getBean(ChatMemoryRecordRepository.class).findBySessionId(session)).isEmpty();
            assertThat(app.getBean(ChatMemoryStore.class).getMessages(new OwnerMemoryId(owner, session))).isEmpty();
            TenantContext.clear();
        }
    }
}
