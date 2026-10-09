package com.coffer.desktop;

import com.coffer.CofferApplication;
import com.coffer.auth.domain.*;
import com.coffer.auth.infrastructure.AppUserRepository;
import com.coffer.auth.service.TenantContext;
import com.coffer.file.application.*;
import com.coffer.file.domain.*;
import com.coffer.file.infrastructure.persistence.*;
import com.coffer.file.infrastructure.storage.PathGenerator;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.governance.api.dto.*;
import com.coffer.governance.application.*;
import com.coffer.governance.domain.*;
import com.coffer.governance.infrastructure.persistence.*;
import com.coffer.model.runtime.ModelExecutionContext;
import com.coffer.model.runtime.ModelContentGate;
import com.coffer.tag.api.dto.TagAndCategoryResult;
import com.coffer.tag.domain.ConfirmationStatus;
import com.coffer.tool.TagGenerationTool;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Predicate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;

/** The real shared preview/archive/rollback services execute against H2 and the desktop adapter. */
class DesktopGovernanceIntegrationTest {
    @TempDir Path temp;
    private static final byte[] BODY = "offline-local-governance-original".getBytes(StandardCharsets.UTF_8);
    @Configuration(proxyBeanMethods = false)
    static class LocalModelFixture {
        @Bean @Primary TagGenerationTool localTags() {
            var tool = mock(TagGenerationTool.class);
            when(tool.generateTagAndCategory(anyString())).thenReturn(new TagAndCategoryResult(CategoryType.CONTRACT, List.of("confirmed-new")));
            return tool;
        }
        @Bean @Primary ModelContentGate contentGate() { return mock(ModelContentGate.class); }
    }
    @AfterEach void clear() {
        TenantContext.clear();
        var logs = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        var file = logs.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("FILE");
        if (file instanceof ch.qos.logback.core.FileAppender<?> output && Path.of(output.getFile()).startsWith(temp)) output.stop();
    }
    private ConfigurableApplicationContext start() {
        var ctx = new SpringApplicationBuilder(CofferApplication.class, LocalModelFixture.class).run(
                "--spring.profiles.active=desktop", "--coffer.desktop.data-directory=" + temp.resolve("data"),
                "--coffer.desktop.initialize=true", "--server.port=0", "--spring.main.banner-mode=off",
                "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=1", "--spring.data.redis.connect-timeout=100ms", "--spring.data.redis.timeout=100ms",
                "--minio.endpoint=http://127.0.0.1:1", "--coffer.import.inbox.enabled=false", "--coffer.embedding.enabled=false",
                "--coffer.parser.process-isolation=false", "--coffer.storage.deletion-retention-hours=0");
        ctx.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()
                .forEach(org.springframework.scheduling.config.ScheduledTask::cancel);
        long owner = ctx.getBean(AppUserRepository.class).saveAndFlush(new AppUser("r32-user", "disabled-test-login", AuthRole.USER)).getId();
        TenantContext.set(owner);
        return ctx;
    }
    private FileMetadata seed(ConfigurableApplicationContext ctx) {
        var storage = ctx.getBean(FileStoragePort.class);
        String key = ctx.getBean(PathGenerator.class).generateStoragePath("离线资料.txt", "txt");
        var object = storage.write(key, new ByteArrayInputStream(BODY), "text/plain", BODY.length);
        var file = ctx.getBean(FileMetadataRepository.class).saveAndFlush(FileMetadata.builder().fileName("离线资料.txt")
                .fileType("txt").fileSize((long) BODY.length).storagePath(key).contentSha256(object.sha256()).contentEtag(object.etag())
                .taskId(UUID.randomUUID().toString()).status(FileStatus.COMPLETED).category(CategoryType.REPORT)
                .summary("original-summary").archived(false).revision(0L).build());
        new org.springframework.transaction.support.TransactionTemplate(ctx.getBean(org.springframework.transaction.PlatformTransactionManager.class))
                .executeWithoutResult(transaction -> ctx.getBean(ArchiveSnapshotService.class).replaceTags(file.getId(), List.of(
                new ArchiveFormalSnapshot.TagState("confirmed-old", ConfirmationStatus.CONFIRMED, "original-note"),
                new ArchiveFormalSnapshot.TagState("pending-old", ConfirmationStatus.PENDING_CONFIRMATION, null))));
        return file;
    }
    private GovernancePreviewResponse preview(ConfigurableApplicationContext ctx, FileMetadata file) {
        long owner = TenantContext.requireOwnerId();
        var fixture = new ModelExecutionContext.Snapshot(UUID.randomUUID().toString(), owner, "r32-local-fixture", GovernanceRunMode.LOCAL, Map.of());
        return ModelExecutionContext.with(fixture, () -> ctx.getBean(GovernancePreviewService.class)
                .create(new CreateGovernancePreviewRequest(List.of(file.getId()), UUID.randomUUID().toString(), GovernancePreviewSource.UPLOAD, GovernanceRunMode.LOCAL)));
    }
    private ArchiveOperationItem archive(ConfigurableApplicationContext ctx, GovernancePreviewResponse preview) throws Exception {
        var confirmed = ctx.getBean(GovernancePreviewService.class).confirm(preview.previewId(),
                new ConfirmGovernancePreviewRequest(UUID.randomUUID().toString(), List.of(), true));
        String batch = confirmed.latestOperation().batchId();
        return await(ctx, batch, item -> item.getExecutionStatus() == ArchiveOperationItemExecutionStatus.SUCCEEDED
                || item.getExecutionStatus() == ArchiveOperationItemExecutionStatus.CONFLICTED);
    }
    private ArchiveOperationItem await(ConfigurableApplicationContext ctx, String batch, Predicate<ArchiveOperationItem> done) throws Exception {
        var repo = ctx.getBean(ArchiveOperationItemRepository.class);
        for (int i=0; i<120; i++) {
            var item = repo.findByBatchIdOrderByIdAsc(batch).get(0);
            if (done.test(item)) return item;
            Thread.sleep(50);
        }
        throw new AssertionError("Local governance did not reach its expected durable state");
    }
    private byte[] read(ConfigurableApplicationContext ctx, String path) throws Exception {
        try (var input = ctx.getBean(FileStoragePort.class).read(path)) { return input.readAllBytes(); }
    }
    @Test void actualPreviewDoesNotPolluteFormalFilesAndOfflineArchiveRollbackRestoreTheCompleteSnapshot() throws Exception {
        try (var ctx = start()) {
            var file = seed(ctx); var storage = ctx.getBean(FileStoragePort.class); var snapshots = ctx.getBean(ArchiveSnapshotService.class);
            var original = snapshots.capture(file, storage.stat(file.getStoragePath()));
            var beforeKeys = storage.listOwnedKeys();
            var preview = preview(ctx, file); assertThat(preview.items().get(0).status()).isEqualTo(GovernancePreviewItemStatus.READY);
            assertThat(preview.items().get(0).suggestedPath()).startsWith("users/" + TenantContext.requireOwnerId() + "/managed/archive/");
            assertThat(snapshots.matches(ctx.getBean(FileMetadataRepository.class).findById(file.getId()).orElseThrow(), original)).isTrue();
            assertThat(storage.listOwnedKeys()).containsExactlyInAnyOrderElementsOf(beforeKeys);
            assertThat(read(ctx, original.path())).isEqualTo(BODY);
            var item = archive(ctx, preview);
            assertThat(item.getExecutionStatus()).isEqualTo(ArchiveOperationItemExecutionStatus.SUCCEEDED);
            assertThat(item.getTargetPath()).isEqualTo(preview.items().get(0).suggestedPath());
            assertThat(storage.exists(original.path())).isFalse(); assertThat(read(ctx, item.getTargetPath())).isEqualTo(BODY);
            ctx.getBean(ArchiveRollbackService.class).requestBatch(item.getBatchId());
            var rolledBack = await(ctx, item.getBatchId(), row -> row.getRollbackStatus() == ArchiveOperationItemRollbackStatus.SUCCEEDED);
            var restored = ctx.getBean(FileMetadataRepository.class).findById(file.getId()).orElseThrow();
            assertThat(snapshots.matchesAfterRollback(restored, original, rolledBack.getRollbackResultRevision())).isTrue();
            assertThat(storage.exists(item.getTargetPath())).isFalse(); assertThat(read(ctx, restored.getStoragePath())).isEqualTo(BODY);
        }
    }
    @Test void occupiedArchiveAndRollbackTargetsPreserveBothExistingFilesAndExposeConflicts() throws Exception {
        try (var ctx = start()) {
            var file = seed(ctx); var storage = ctx.getBean(FileStoragePort.class); var preview = preview(ctx, file);
            byte[] occupied = "existing-user-content".getBytes(StandardCharsets.UTF_8);
            storage.write(preview.items().get(0).suggestedPath(), new ByteArrayInputStream(occupied), "text/plain", occupied.length);
            var conflicted = ctx.getBean(GovernancePreviewService.class).confirm(preview.previewId(),
                    new ConfirmGovernancePreviewRequest(UUID.randomUUID().toString(), List.of(), true));
            assertThat(conflicted.items().get(0).status()).isEqualTo(GovernancePreviewItemStatus.CONFLICTED);
            assertThat(conflicted.latestOperation()).isNull();
            assertThat(ctx.getBean(FileMetadataRepository.class).findById(file.getId()).orElseThrow().getStoragePath()).isEqualTo(file.getStoragePath());
            assertThat(read(ctx, file.getStoragePath())).isEqualTo(BODY);
            assertThat(read(ctx, preview.items().get(0).suggestedPath())).isEqualTo(occupied);
            var fresh = preview(ctx, file); var archived = archive(ctx, fresh);
            storage.write(archived.getSourcePath(), new ByteArrayInputStream(occupied), "text/plain", occupied.length);
            ctx.getBean(ArchiveRollbackService.class).requestBatch(archived.getBatchId());
            var failed = await(ctx, archived.getBatchId(), row -> row.getRollbackStatus() == ArchiveOperationItemRollbackStatus.CONFLICTED);
            assertThat(failed.getFailureCode()).isEqualTo("ROLLBACK_CONFLICT");
            assertThat(read(ctx, archived.getSourcePath())).isEqualTo(occupied);
            assertThat(read(ctx, archived.getTargetPath())).isEqualTo(BODY);
            assertThat(ctx.getBean(ArchiveOperationService.class).get(archived.getBatchId()).items().get(0).rollbackStatus())
                    .isEqualTo(ArchiveOperationItemRollbackStatus.CONFLICTED);
        }
    }
    @Test void staleArchiveCleanupCannotDeleteTheSourceRestoredByRollback() throws Exception {
        try (var ctx = start()) {
            var file = seed(ctx); var item = archive(ctx, preview(ctx, file));
            var rollback = ctx.getBean(ArchiveRollbackPersistenceService.class); rollback.prepareBatch(item.getBatchId()); rollback.claim(item.getId());
            rollback.markCopying(item.getId());
            var restored = ctx.getBean(FileStoragePort.class).copy(item.getTargetPath(), item.getSourcePath(), item.getTargetSha256());
            rollback.markDbCommitting(item.getId()); rollback.restoreMetadata(item.getId(), restored.etag(), restored.sha256());
            var task = ctx.getBean(GovernanceCompensationRegistry.class).register(item.getBatchId(), item.getId(),
                    GovernanceCompensationAction.DELETE_ARCHIVE_SOURCE, item.getSourcePath(), "fixture-stale-cleanup");
            ctx.getBean(GovernanceCompensationProcessor.class).process(task.getId());
            assertThat(ctx.getBean(GovernanceCompensationTaskRepository.class).findById(task.getId()).orElseThrow().getStatus())
                    .isEqualTo(GovernanceCompensationStatus.MANUAL_REVIEW);
            assertThat(read(ctx, item.getSourcePath())).isEqualTo(BODY); assertThat(read(ctx, item.getTargetPath())).isEqualTo(BODY);
            assertThat(ctx.getBean(ArchiveOperationItemRepository.class).findById(item.getId()).orElseThrow().getExecutionStatus())
                    .isEqualTo(ArchiveOperationItemExecutionStatus.SUCCEEDED);
            assertThat(ctx.getBean(FileMetadataRepository.class).findById(file.getId()).orElseThrow().getStoragePath()).isEqualTo(item.getSourcePath());
        }
    }
    @Test void staleRollbackCleanupCannotDeleteTheTargetAfterTheFormalStateChanged() throws Exception {
        try (var ctx = start()) {
            var file = seed(ctx); var item = archive(ctx, preview(ctx, file));
            ctx.getBean(FileStoragePort.class).copy(item.getTargetPath(), item.getSourcePath(), item.getTargetSha256());
            var task = ctx.getBean(GovernanceCompensationRegistry.class).register(item.getBatchId(), item.getId(),
                    GovernanceCompensationAction.DELETE_ROLLBACK_TARGET, item.getTargetPath(), "fixture-stale-cleanup");
            ctx.getBean(GovernanceCompensationProcessor.class).process(task.getId());
            assertThat(ctx.getBean(GovernanceCompensationTaskRepository.class).findById(task.getId()).orElseThrow().getStatus())
                    .isEqualTo(GovernanceCompensationStatus.MANUAL_REVIEW);
            assertThat(read(ctx, item.getTargetPath())).isEqualTo(BODY); assertThat(read(ctx, item.getSourcePath())).isEqualTo(BODY);
            assertThat(ctx.getBean(ArchiveOperationItemRepository.class).findById(item.getId()).orElseThrow().getRollbackStatus())
                    .isEqualTo(ArchiveOperationItemRollbackStatus.NOT_REQUESTED);
            assertThat(ctx.getBean(FileMetadataRepository.class).findById(file.getId()).orElseThrow().getStoragePath()).isEqualTo(item.getTargetPath());
        }
    }
    @Test void physicalDeletionFailureRemainsQueryableAndExplicitRetryCompletesAfterIdentityRepair() throws Exception {
        try (var ctx = start()) {
            var file = seed(ctx); var storage = ctx.getBean(FileStoragePort.class);
            ctx.getBean(FileOperationService.class).deleteFile(file.getId());
            var repository = ctx.getBean(StorageDeletionTaskRepository.class); var task = repository.findAll().get(0);
            Path body = ctx.getBean(DesktopDataDirectory.class).libraryRoot().resolve(file.getStoragePath());
            Files.writeString(body, "changed-unverified-bytes");
            var processor = ctx.getBean(StorageDeletionProcessor.class); processor.process(task.getId());
            assertThat(ctx.getBean(StorageDeletionTaskService.class).recent().get(0).status()).isEqualTo(StorageDeletionStatus.FAILED);
            var retry = repository.findById(task.getId()).orElseThrow(); retry.setNextAttemptAt(LocalDateTime.now().minusSeconds(1)); repository.saveAndFlush(retry);
            processor.process(task.getId());
            assertThat(ctx.getBean(StorageDeletionTaskService.class).recent().get(0).status()).isEqualTo(StorageDeletionStatus.MANUAL_REVIEW);
            assertThat(Files.readString(body)).isEqualTo("changed-unverified-bytes");
            Files.write(body, BODY); ctx.getBean(StorageDeletionTaskService.class).retry(task.getId()); processor.process(task.getId());
            assertThat(repository.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(StorageDeletionStatus.SUCCEEDED);
            assertThat(storage.exists(file.getStoragePath())).isFalse(); assertThat(ctx.getBean(FileMetadataRepository.class).findById(file.getId())).isEmpty();
        }
    }
}
