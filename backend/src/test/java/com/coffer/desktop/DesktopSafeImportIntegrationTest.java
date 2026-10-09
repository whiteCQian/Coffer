package com.coffer.desktop;

import com.coffer.CofferApplication;
import com.coffer.auth.domain.*;
import com.coffer.auth.infrastructure.AppUserRepository;
import com.coffer.auth.service.TenantContext;
import com.coffer.file.application.FileUploadApplicationService;
import com.coffer.file.domain.*;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.inbox.application.*;
import com.coffer.inbox.domain.InboxImportStatus;
import com.coffer.inbox.infrastructure.persistence.InboxImportRecordRepository;
import com.coffer.model.runtime.ModelExecutionContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.ByteArrayInputStream;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DesktopSafeImportIntegrationTest {
    @TempDir Path temp;
    @AfterEach void clear() {
        TenantContext.clear();
        var logs = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        var file = logs.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("FILE");
        if (file instanceof ch.qos.logback.core.FileAppender<?> output && Path.of(output.getFile()).startsWith(temp)) output.stop();
    }
    private ConfigurableApplicationContext start(Path data, boolean initialize, String... extra) {
        var args = new ArrayList<>(List.of("--spring.profiles.active=desktop", "--coffer.desktop.data-directory=" + data,
                "--coffer.desktop.initialize=" + initialize, "--server.port=0", "--spring.main.banner-mode=off",
                "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=1", "--spring.data.redis.timeout=100ms",
                "--coffer.import.inbox.fixed-delay-ms=600000", "--coffer.embedding.enabled=false"));
        args.addAll(List.of(extra));
        var context = new SpringApplicationBuilder(CofferApplication.class).run(args.toArray(String[]::new));
        // Drive scans explicitly; an immediate first scheduler tick must not race fixture setup.
        context.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class)
                .getScheduledTasks().forEach(org.springframework.scheduling.config.ScheduledTask::cancel);
        return context;
    }
    private long owner(ConfigurableApplicationContext ctx, String name) {
        return ctx.getBean(AppUserRepository.class).saveAndFlush(new AppUser(name, "test-disabled-login", AuthRole.USER)).getId();
    }
    private void confirm(ConfigurableApplicationContext ctx, long id, String target, long owner) {
        // Internal fixture tests local I/O; public HTTP requests still require ModelSubmission's real authorization.
        var fixture = new ModelExecutionContext.Snapshot(UUID.randomUUID().toString(), owner, "io-fixture",
                com.coffer.governance.domain.GovernanceRunMode.LOCAL, Map.of());
        ModelExecutionContext.with(fixture, () -> ctx.getBean(InboxImportScanner.class).confirm(id, target));
    }
    @Test void previewFixesTargetAndOnlyConfirmedOwnersFileIsCopied() throws Exception {
        try (var ctx = start(temp.resolve("data"), true)) {
            long a = owner(ctx, "import-a"), b = owner(ctx, "import-b"); TenantContext.set(a);
            Path workspace = ctx.getBean(DesktopLibraryLayout.class).ensureOwnerWorkspace();
            for (String area : DesktopLibraryLayout.AREAS) assertThat(workspace.resolve(area)).isDirectory();
            Path source = Files.writeString(workspace.resolve("inbox/报告.txt"), "source-original");
            var scanner = ctx.getBean(InboxImportScanner.class);
            scanner.scanOnce(); scanner.scanOnce();
            var rows = ctx.getBean(InboxImportRecordRepository.class);
            var record = rows.findAll().get(0);
            assertThat(record.getStatus()).isEqualTo(InboxImportStatus.AWAITING_CONFIRMATION);
            assertThat(record.getTargetPath()).startsWith("users/" + a + "/managed/files/");
            assertThat(ctx.getBean(FileMetadataRepository.class).count()).isZero();
            assertThat(ctx.getBean(FileStoragePort.class).listOwnedKeys()).isEmpty();
            assertThatThrownBy(() -> confirm(ctx, record.getId(), record.getTargetPath() + "-changed", a)).isInstanceOf(RuntimeException.class);
            TenantContext.set(b);
            assertThatThrownBy(() -> confirm(ctx, record.getId(), record.getTargetPath(), b)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> ctx.getBean(InboxDirectoryResolver.class).requireSource(source)).isInstanceOf(SecurityException.class);
            TenantContext.set(a);
            confirm(ctx, record.getId(), record.getTargetPath(), a);
            confirm(ctx, record.getId(), record.getTargetPath(), a);
            assertThat(rows.findById(record.getId()).orElseThrow().getStatus()).isEqualTo(InboxImportStatus.IMPORTED);
            var saved = ctx.getBean(FileMetadataRepository.class).findAll().get(0);
            assertThat(saved.getStoragePath()).isEqualTo(record.getTargetPath());
            assertThat(Files.readString(source)).isEqualTo("source-original");
            try (var input = ctx.getBean(FileStoragePort.class).read(saved.getStoragePath())) {
                assertThat(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("source-original");
            }
            TenantContext.set(b);
            Path bSource = Files.writeString(ctx.getBean(DesktopLibraryLayout.class).inbox().resolve("报告.txt"), "source-original");
            scanner.scanOnce(); scanner.scanOnce();
            var bRecord = rows.findAll().get(0);
            assertThat(bRecord.getStatus()).isEqualTo(InboxImportStatus.AWAITING_CONFIRMATION);
            assertThat(bRecord.getTargetPath()).startsWith("users/" + b + "/managed/");
            confirm(ctx, bRecord.getId(), bRecord.getTargetPath(), b);
            assertThat(Files.readString(bSource)).isEqualTo("source-original");
            assertThat(ctx.getBean(FileMetadataRepository.class).count()).isEqualTo(1);
        }
    }
    @Test void occupiedSourceChangedContentAndTargetConflictNeverRemoveOrOverwriteOriginals() throws Exception {
        try (var ctx = start(temp.resolve("data"), true)) {
            long user = owner(ctx, "import-user"); TenantContext.set(user);
            Path inbox = ctx.getBean(DesktopLibraryLayout.class).inbox();
            Path source = Files.writeString(inbox.resolve("locked.txt"), "original");
            var scanner = ctx.getBean(InboxImportScanner.class); scanner.scanOnce(); scanner.scanOnce();
            var repo = ctx.getBean(InboxImportRecordRepository.class); var record = repo.findAll().get(0);
            try (var held = FileChannel.open(source, StandardOpenOption.WRITE); var lock = held.lock()) {
                assertThatThrownBy(() -> confirm(ctx, record.getId(), record.getTargetPath(), user)).isInstanceOf(RuntimeException.class);
            }
            assertThat(ctx.getBean(FileMetadataRepository.class).count()).isZero();
            assertThat(Files.readString(source)).isEqualTo("original");
            scanner.retry(record.getId());
            byte[] occupied = "already-present".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ctx.getBean(FileStoragePort.class).write(record.getTargetPath(), new ByteArrayInputStream(occupied), "text/plain", occupied.length);
            assertThatThrownBy(() -> confirm(ctx, record.getId(), record.getTargetPath(), user)).isInstanceOf(RuntimeException.class);
            try (var input = ctx.getBean(FileStoragePort.class).read(record.getTargetPath())) { assertThat(input.readAllBytes()).isEqualTo(occupied); }
            assertThat(Files.readString(source)).isEqualTo("original");
            assertThat(ctx.getBean(FileMetadataRepository.class).count()).isZero();
            Path outside = Files.writeString(temp.resolve("outside.txt"), "foreign");
            assertThatThrownBy(() -> ctx.getBean(FileUploadApplicationService.class).importInboxFile(outside, Files.size(outside),
                    Files.getLastModifiedTime(outside).toMillis())).isInstanceOf(SecurityException.class);
            assertThat(Files.readString(outside)).isEqualTo("foreign");
            Path changing = Files.writeString(inbox.resolve("changing.txt"), "before");
            scanner.scanOnce(); scanner.scanOnce();
            var preview = repo.findAll().stream().filter(r -> r.getSourceFileName().equals("changing.txt")).findFirst().orElseThrow();
            var timestamp = Files.getLastModifiedTime(changing);
            Files.writeString(changing, "after!"); Files.setLastModifiedTime(changing, timestamp);
            assertThatThrownBy(() -> confirm(ctx, preview.getId(), preview.getTargetPath(), user)).isInstanceOf(RuntimeException.class);
            scanner.scanOnce(); scanner.scanOnce();
            assertThat(repo.findAll().stream().filter(r -> r.getSourceFileName().equals("changing.txt") && r.getStatus() == InboxImportStatus.AWAITING_CONFIRMATION)).hasSize(1);
            assertThat(Files.readString(changing)).isEqualTo("after!");
        }
    }
    @Test void emptyExternalLibraryRequiresExplicitRebindAndMatchingBodyDigests() throws Exception {
        Path data = temp.resolve("data"); String key; byte[] body = "bound-original".getBytes();
        try (var ctx = start(data, true)) {
            long user = owner(ctx, "bound-user"); TenantContext.set(user);
            ctx.getBean(DesktopLibraryLayout.class).ensureOwnerWorkspace();
            key = "users/" + user + "/managed/files/original.txt";
            var object = ctx.getBean(FileStoragePort.class).write(key, new ByteArrayInputStream(body), "text/plain", body.length);
            ctx.getBean(FileMetadataRepository.class).saveAndFlush(FileMetadata.builder().fileName("original.txt").fileType("txt")
                    .fileSize((long) body.length).storagePath(key).contentSha256(object.sha256()).status(FileStatus.COMPLETED).build());
            TenantContext.clear();
        }
        Path moved = temp.resolve("moved-library"); Files.move(data.resolve("library"), moved);
        assertStartupReason(() -> start(data, false, "--coffer.desktop.library-directory=" + moved), DesktopStartupException.Reason.REBIND_REQUIRED);
        Files.writeString(moved.resolve(key), "corrupt");
        assertStartupReason(() -> start(data, false, "--coffer.desktop.library-directory=" + moved, "--coffer.desktop.rebind-library=true"),
                DesktopStartupException.Reason.LIBRARY_CONTENT_MISMATCH);
        Files.write(moved.resolve(key), body);
        try (var ctx = start(data, false, "--coffer.desktop.library-directory=" + moved, "--coffer.desktop.rebind-library=true")) {
            assertThat(ctx.getBean(JdbcTemplate.class).queryForObject("SELECT library_root FROM desktop_library_binding WHERE id=1", String.class)).isEqualTo(moved.toString());
            assertThat(ctx.getBean(DesktopDataDirectory.class).requiredBackupEntries()).contains(moved.toString());
            long user = ctx.getBean(JdbcTemplate.class).queryForObject("SELECT id FROM app_user WHERE username='bound-user'", Long.class);
            TenantContext.set(user);
            var now = java.time.LocalDateTime.now();
            ctx.getBean(InboxImportRecordRepository.class).saveAndFlush(com.coffer.inbox.domain.InboxImportRecord.builder()
                    .snapshotKey("9".repeat(64)).sourcePath("pending.txt").sourceFileName("pending.txt").sourceSize(1L)
                    .sourceModifiedAt(now).status(InboxImportStatus.IMPORTING).importStartedAt(now)
                    .firstSeenAt(now).lastSeenAt(now).createdAt(now).updatedAt(now).build());
            TenantContext.clear();
        }
        Path secondMove = temp.resolve("second-move"); Files.move(moved, secondMove);
        assertStartupReason(() -> start(data, false, "--coffer.desktop.library-directory=" + secondMove, "--coffer.desktop.rebind-library=true"),
                DesktopStartupException.Reason.REBIND_PENDING);
    }
    @Test void occupiedUnboundOwnerDirectoryAndNonemptyLibraryCannotBeInitialized() throws Exception {
        Path external = Files.createDirectory(temp.resolve("occupied-library")); Files.writeString(external.resolve("original.txt"), "keep");
        assertStartupReason(() -> start(temp.resolve("refused-data"), true, "--coffer.desktop.library-directory=" + external),
                DesktopStartupException.Reason.INITIALIZATION_REFUSED);
        assertThat(Files.readString(external.resolve("original.txt"))).isEqualTo("keep");
        try (var ctx = start(temp.resolve("data"), true)) {
            long user = owner(ctx, "owner-area"); TenantContext.set(user);
            Path workspace = Files.createDirectories(ctx.getBean(DesktopDataDirectory.class).libraryRoot().resolve("users/" + user));
            Files.writeString(workspace.resolve("unknown.txt"), "keep");
            assertThatThrownBy(() -> ctx.getBean(DesktopLibraryLayout.class).ensureOwnerWorkspace()).isInstanceOf(IllegalStateException.class);
            assertThat(workspace.resolve(".coffer-owner.json")).doesNotExist();
            assertThat(Files.readString(workspace.resolve("unknown.txt"))).isEqualTo("keep");
        }
    }
    private void assertStartupReason(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, DesktopStartupException.Reason reason) {
        assertThatThrownBy(action).satisfies(error -> {
            Throwable cause = error; while (cause != null && !(cause instanceof DesktopStartupException)) cause = cause.getCause();
            assertThat(cause).isInstanceOf(DesktopStartupException.class);
            assertThat(((DesktopStartupException) cause).reason()).isEqualTo(reason);
        });
    }
}
