package com.coffer.desktop;

import com.coffer.CofferApplication;
import com.coffer.auth.domain.*;
import com.coffer.auth.infrastructure.AppUserRepository;
import com.coffer.auth.service.TenantContext;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.memory.*;
import com.coffer.privacy.MemoryDeletionWorker;
import com.coffer.privacy.PrivacyService;
import com.coffer.service.ChatSessionService;
import com.coffer.service.SecretCryptoService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class DesktopProductionIntegrationTest {
    @TempDir Path temp;
    @AfterEach void clearOwner() {
        TenantContext.clear();
        // Spring reconfigures the process-wide logger for each application context. Release its
        // last file handle before JUnit removes the isolated Windows data directory.
        var loggers = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        var appender = loggers.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("FILE");
        if (appender instanceof ch.qos.logback.core.FileAppender<?> file
                && Path.of(file.getFile()).startsWith(temp)) file.stop();
    }

    private ConfigurableApplicationContext start(Path root, boolean initialize, String... extras) {
        List<String> args = new ArrayList<>(List.of("--spring.profiles.active=desktop", "--server.port=0",
                "--coffer.desktop.data-directory=" + root, "--coffer.desktop.initialize=" + initialize,
                "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=1",
                "--spring.data.redis.connect-timeout=100ms", "--spring.data.redis.timeout=100ms",
                "--minio.endpoint=http://127.0.0.1:1", "--coffer.embedding.enabled=false", "--coffer.hybrid.enabled=false",
                "--spring.main.banner-mode=off"));
        args.addAll(List.of(extras));
        return new SpringApplicationBuilder(CofferApplication.class).run(args.toArray(String[]::new));
    }

    @Test void isolatedDesktopPersistsFilesToolMemoryAndCredentialsWithoutExternalServices() throws Exception {
        Path root = temp.resolve("desktop");
        Long owner, other;
        String session, otherSession, ciphertext;
        byte[] body = "offline-file".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var request = ToolExecutionRequest.builder().id("tool-1").name("search_files").arguments("{}").build();
        List<ChatMessage> frames = List.of(UserMessage.from("find"), AiMessage.from(request),
                ToolExecutionResultMessage.from("tool-1", "search_files", "found"), AiMessage.from("done"));
        try (var context = start(root, true, "--server.address=0.0.0.0", "--spring.h2.console.enabled=true")) {
            var env = context.getEnvironment();
            assertThat(env.getProperty("server.address")).isEqualTo("127.0.0.1");
            assertThat(env.getProperty("spring.h2.console.enabled", Boolean.class)).isFalse();
            assertThat(env.getProperty("spring.datasource.url")).doesNotContain("AUTO_SERVER=TRUE");
            assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            assertThat(context.getBean(ChatMemoryStore.class)).isInstanceOf(DatabaseChatMemoryStore.class);
            assertThat(context.getBeansOfType(RedisChatMemoryStore.class)).isEmpty();
            assertThat(context.containsBean("minioClient")).isFalse();
            assertThat(env.getProperty("COFFER_LOG_DIR")).isEqualTo(root.resolve("logs").toString());
            var jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM desktop_library_binding", Long.class)).isEqualTo(1);
            var users = context.getBean(AppUserRepository.class);
            owner = users.saveAndFlush(new AppUser("desktop-a", "disabled-test-login", AuthRole.USER)).getId();
            other = users.saveAndFlush(new AppUser("desktop-b", "disabled-test-login", AuthRole.USER)).getId();
            TenantContext.set(owner);
            session = context.getBean(ChatSessionService.class).create();
            context.getBean(ChatMemoryStore.class).updateMessages(new OwnerMemoryId(owner, session), frames);
            assertThat(context.getBean(ChatMemoryStore.class).getMessages(new OwnerMemoryId(owner, session))).isEqualTo(frames);
            context.getBean(FileStoragePort.class).write("users/" + owner + "/files/offline.txt", new ByteArrayInputStream(body), "text/plain", body.length);
            TenantContext.set(other);
            otherSession = context.getBean(ChatSessionService.class).create();
            context.getBean(ChatMemoryStore.class).updateMessages(new OwnerMemoryId(other, otherSession), List.of(UserMessage.from("B_PRIVATE")));
            assertThatThrownBy(() -> context.getBean(ChatMemoryStore.class).getMessages(new OwnerMemoryId(owner, session))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> context.getBean(ChatMemoryStore.class).getMessages("raw-key")).isInstanceOf(RuntimeException.class);
            TenantContext.clear();
            ciphertext = context.getBean(SecretCryptoService.class).encrypt("test-model-credential");

            String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            Process second = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", cp, DesktopLockProbeMain.class.getName(), root.toString()).redirectErrorStream(true).start();
            try {
                assertThat(second.waitFor(20, TimeUnit.SECONDS)).isTrue();
                assertThat(second.exitValue()).isEqualTo(23);
                assertThat(new String(second.getInputStream().readAllBytes())).contains("LOCK_REJECTED=IN_USE");
            } finally { second.destroyForcibly(); }
        }
        byte[] originalKey = Files.readAllBytes(root.resolve(DesktopDataDirectory.KEY_FILE));
        try (var context = start(root, false)) {
            assertThat(context.getEnvironment().getProperty("spring.datasource.url")).contains("IFEXISTS=TRUE");
            assertThat(context.getBean(SecretCryptoService.class).decrypt(ciphertext)).isEqualTo("test-model-credential");
            assertThat(Files.readAllBytes(root.resolve(DesktopDataDirectory.KEY_FILE))).isEqualTo(originalKey);
            TenantContext.set(owner);
            assertThat(context.getBean(ChatMemoryStore.class).getMessages(new OwnerMemoryId(owner, session))).isEqualTo(frames);
            try (var source = context.getBean(FileStoragePort.class).read("users/" + owner + "/files/offline.txt")) {
                assertThat(source.readAllBytes()).isEqualTo(body);
            }
            // Deleting the session first must still allow the queued database memory erase, without Redis.
            context.getBean(PrivacyService.class).eraseConversations("DELETE_MY_CONVERSATIONS");
            context.getBean(MemoryDeletionWorker.class).process();
            assertThat(context.getBean(ChatMemoryRecordRepository.class).count()).isZero();
            TenantContext.set(other);
            assertThat(context.getBean(ChatMemoryStore.class).getMessages(new OwnerMemoryId(other, otherSession))).containsExactly(UserMessage.from("B_PRIVATE"));
            var record = context.getBean(ChatMemoryRecordRepository.class).findBySessionId(otherSession).orElseThrow();
            record.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
            context.getBean(ChatMemoryRecordRepository.class).saveAndFlush(record);
            assertThat(context.getBean(ChatMemoryStore.class).getMessages(new OwnerMemoryId(other, otherSession))).isEmpty();
            // Configuration changes clear old tool memory before it can reach a different model target.
            var mode = com.coffer.governance.domain.GovernanceRunMode.LOCAL;
            var v1 = new com.coffer.model.runtime.ModelExecutionContext.Snapshot("v1", other, "version-1", mode, Map.of());
            var v2 = new com.coffer.model.runtime.ModelExecutionContext.Snapshot("v2", other, "version-2", mode, Map.of());
            com.coffer.model.runtime.ModelExecutionContext.with(v1, () ->
                    context.getBean(ChatMemoryStore.class).updateMessages(new OwnerMemoryId(other, otherSession), List.of(UserMessage.from("old-endpoint"))));
            assertThat(com.coffer.model.runtime.ModelExecutionContext.with(v2, () ->
                    context.getBean(ChatMemoryStore.class).getMessages(new OwnerMemoryId(other, otherSession)))).isEmpty();
            var files = context.getBean(com.coffer.file.infrastructure.persistence.FileMetadataRepository.class);
            var file = files.saveAndFlush(com.coffer.file.domain.FileMetadata.builder().fileName("evidence.txt")
                    .fileType("txt").fileSize(1L).storagePath("users/" + other + "/files/evidence.txt")
                    .status(com.coffer.file.domain.FileStatus.COMPLETED).revision(0L).build());
            var citations = context.getBean(com.coffer.service.ChatCitationCollector.class);
            citations.begin();
            try {
                citations.capture(file, 1.0, "KEYWORD", null);
                context.getBean(ChatMemoryStore.class).updateMessages(new OwnerMemoryId(other, otherSession), List.of(UserMessage.from("revision-0")));
            } finally { citations.clear(); }
            file.setRevision(1L); files.saveAndFlush(file);
            assertThat(context.getBean(ChatMemoryStore.class).getMessages(new OwnerMemoryId(other, otherSession))).isEmpty();
            TenantContext.clear();
        }
    }

    @Test void changedDatabaseBindingAndWrongKeyBlockStartupWithoutReplacingData() throws Exception {
        Path root = temp.resolve("bound");
        String goodLibraryId;
        try (var context = start(root, true)) {
            var jdbc = context.getBean(JdbcTemplate.class);
            goodLibraryId = jdbc.queryForObject("SELECT library_id FROM desktop_library_binding WHERE id=1", String.class);
            jdbc.update("UPDATE desktop_library_binding SET library_id=? WHERE id=1", UUID.randomUUID().toString());
        }
        byte[] key = Files.readAllBytes(root.resolve(DesktopDataDirectory.KEY_FILE));
        assertStartupReason(() -> start(root, false), DesktopStartupException.Reason.BINDING_INVALID);
        // Direct embedded H2 repair is allowed only after the failed process has closed and released its lock.
        try (var connection = java.sql.DriverManager.getConnection("jdbc:h2:file:" + root.resolve("database/coffer").toString().replace('\\','/') + ";IFEXISTS=TRUE", "sa", "");
             var update = connection.prepareStatement("UPDATE desktop_library_binding SET library_id=? WHERE id=1")) {
            update.setString(1, goodLibraryId); update.executeUpdate();
        }
        byte[] replacement = new byte[32]; new java.security.SecureRandom().nextBytes(replacement);
        Files.writeString(root.resolve(DesktopDataDirectory.KEY_FILE), Base64.getEncoder().encodeToString(replacement));
        assertStartupReason(() -> start(root, false), DesktopStartupException.Reason.KEY_MISMATCH);
        Files.write(root.resolve(DesktopDataDirectory.KEY_FILE), key);
        try (var context = start(root, false)) { assertThat(context.isActive()).isTrue(); }
    }

    private void assertStartupReason(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, DesktopStartupException.Reason reason) {
        assertThatThrownBy(action).satisfies(failure -> {
            Throwable cause = failure;
            while (cause != null && !(cause instanceof DesktopStartupException)) cause = cause.getCause();
            assertThat(cause).isInstanceOf(DesktopStartupException.class);
            assertThat(((DesktopStartupException) cause).reason()).isEqualTo(reason);
        });
    }
}
