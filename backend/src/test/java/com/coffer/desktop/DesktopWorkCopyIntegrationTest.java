package com.coffer.desktop;

import com.coffer.CofferApplication;
import com.coffer.auth.domain.*;
import com.coffer.auth.infrastructure.AppUserRepository;
import com.coffer.auth.service.*;
import com.coffer.file.application.*;
import com.coffer.file.application.async.AsyncFileProcessor;
import com.coffer.file.domain.*;
import com.coffer.file.infrastructure.persistence.*;
import com.coffer.file.infrastructure.storage.PathGenerator;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.model.runtime.ModelExecutionContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import java.io.ByteArrayInputStream;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DesktopWorkCopyIntegrationTest {
    @TempDir Path temp;
    @Configuration(proxyBeanMethods = false) static class Fixtures {
        @Bean @Primary WorkCopyOpener openerFixture() { return mock(WorkCopyOpener.class); }
        @Bean @Primary AsyncFileProcessor processorFixture() { return mock(AsyncFileProcessor.class); }
    }
    @AfterEach void clear() {
        TenantContext.clear();
        var logs = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        var file = logs.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("FILE");
        if (file instanceof ch.qos.logback.core.FileAppender<?> output && Path.of(output.getFile()).startsWith(temp)) output.stop();
    }
    private ConfigurableApplicationContext start(boolean initialize) {
        var ctx = new SpringApplicationBuilder(CofferApplication.class, Fixtures.class).run(
                "--spring.profiles.active=desktop", "--coffer.desktop.data-directory=" + temp.resolve("data"),
                "--coffer.desktop.initialize=" + initialize, "--server.port=0", "--spring.main.banner-mode=off",
                "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=1", "--coffer.embedding.enabled=false",
                "--coffer.import.inbox.enabled=false", "--coffer.parser.process-isolation=false");
        ctx.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()
                .forEach(org.springframework.scheduling.config.ScheduledTask::cancel);
        return ctx;
    }
    private long owner(ConfigurableApplicationContext ctx, String username) {
        long owner = ctx.getBean(AppUserRepository.class).saveAndFlush(new AppUser(username, "disabled", AuthRole.USER)).getId();
        TenantContext.set(owner); return owner;
    }
    private FileMetadata seed(ConfigurableApplicationContext ctx, String name) {
        byte[] bytes = "original bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String type = name.substring(name.lastIndexOf('.') + 1);
        String key = ctx.getBean(PathGenerator.class).generateStoragePath(name, type);
        var object = ctx.getBean(FileStoragePort.class).write(key, new ByteArrayInputStream(bytes), null, bytes.length);
        return ctx.getBean(FileMetadataRepository.class).saveAndFlush(FileMetadata.builder().fileName(name).fileType(type)
                .fileSize((long) bytes.length).contentSha256(object.sha256()).storagePath(key)
                .status(FileStatus.COMPLETED).revision(3L).build());
    }
    private WorkCopyService.View save(ConfigurableApplicationContext ctx, String id) {
        return ModelExecutionContext.with(new ModelExecutionContext.Snapshot("r33-local", TenantContext.requireOwnerId(),
                "fixture", GovernanceRunMode.LOCAL, Map.of()), () -> ctx.getBean(WorkCopyService.class).save(id));
    }
    @Test void externalOpenOnlyReceivesWorkPathAndSavingPublishesNewVersionAndAnalysisTask() throws Exception {
        try (var ctx = start(true)) {
            long a = owner(ctx, "work-a"); var file = seed(ctx, "报告.docx"); var copies = ctx.getBean(WorkCopyService.class);
            var view = copies.create(file.getId()); Path work = Path.of(view.workPath());
            assertThat(view.status()).isEqualTo("READY"); assertThat(work).hasContent("original bytes");
            assertThat(work).startsWith(ctx.getBean(DesktopLibraryLayout.class).ensureOwnerWorkspace().resolve("work"));
            copies.open(view.id()); verify(ctx.getBean(WorkCopyOpener.class)).open(work);
            doThrow(new java.io.IOException("fixture app launch failure")).when(ctx.getBean(WorkCopyOpener.class)).open(work);
            assertThat(copies.open(view.id()).errorCode()).isEqualTo("APP_OPEN_FAILED");
            assertThat(work).hasContent("original bytes");
            Path formal = ctx.getBean(DesktopDataDirectory.class).libraryRoot().resolve(file.getStoragePath());
            Files.writeString(work, "edited document bytes"); assertThat(formal).hasContent("original bytes");
            var saved = save(ctx, view.id()); assertThat(saved.status()).isEqualTo("SAVED");
            var updated = ctx.getBean(FileMetadataRepository.class).findById(file.getId()).orElseThrow();
            assertThat(updated.getRevision()).isEqualTo(4L); assertThat(updated.getStoragePath()).isNotEqualTo(file.getStoragePath());
            assertThat(ctx.getBean(DesktopDataDirectory.class).libraryRoot().resolve(updated.getStoragePath())).hasContent("edited document bytes");
            verify(ctx.getBean(AsyncFileProcessor.class)).processFileAsync(updated.getTaskId());
            assertThat(ctx.getBean(StorageDeletionTaskRepository.class).findAll()).hasSize(1);
            assertThat(copies.close(view.id(), "KEEP", null).status()).isEqualTo("SAVED");
            assertThatThrownBy(() -> copies.open(view.id())).isInstanceOf(com.coffer.file.storage.StorageConflictException.class);
            copies.close(view.id(), "REPORT_EXIT", null);
            assertThat(copies.close(view.id(), "CLOSE", saved.sha256()).status()).isEqualTo("CLOSED");
            assertThat(work).doesNotExist(); assertThat(formal).hasContent("original bytes");
            long b = owner(ctx, "work-b"); assertThat(b).isNotEqualTo(a);
            assertThatThrownBy(() -> copies.get(view.id())).isInstanceOf(ResourceNotFoundException.class);
            assertThat(copies.recent(0)).isEmpty();
        }
    }
    @Test void officeOwnerFileAndExclusiveLockBlockSaveAndDiscardWithoutChangingEitherFile() throws Exception {
        try (var ctx = start(true)) {
            owner(ctx, "office-busy"); var file = seed(ctx, "数据.xlsx"); var copies = ctx.getBean(WorkCopyService.class);
            var copy = copies.create(file.getId()); var work = Path.of(copy.workPath()); Files.writeString(work, "unsaved-edited");
            Path ownerFile = Files.writeString(work.resolveSibling("~$it.xlsx"), "Office owner");
            assertThat(save(ctx, copy.id()).errorCode()).isEqualTo("COPY_BUSY_OR_CHANGED");
            assertThat(copies.close(copy.id(), "DISCARD", copy.sha256()).status()).isEqualTo("INTERRUPTED");
            assertThat(work).hasContent("unsaved-edited"); Files.delete(ownerFile);
            try (var channel = FileChannel.open(work, StandardOpenOption.WRITE); var lock = channel.lock()) {
                assertThat(copies.get(copy.id()).busy()).isTrue();
                assertThat(save(ctx, copy.id()).status()).isEqualTo("INTERRUPTED");
            }
            assertThat(copies.get(copy.id()).busy()).isFalse();
            assertThat(ctx.getBean(DesktopDataDirectory.class).libraryRoot().resolve(file.getStoragePath())).hasContent("original bytes");
        }
    }
    @Test void mtimeOnlyChangeIsConflictAndSaveAsNeverReplacesFormalFile() throws Exception {
        try (var ctx = start(true)) {
            owner(ctx, "work-conflict"); var file = seed(ctx, "note.txt"); var copies = ctx.getBean(WorkCopyService.class);
            var copy = copies.create(file.getId()); Path work = Path.of(copy.workPath()); Files.writeString(work, "edited-conflict");
            var formal = ctx.getBean(DesktopDataDirectory.class).libraryRoot().resolve(file.getStoragePath());
            Files.setLastModifiedTime(formal, FileTime.fromMillis(Files.getLastModifiedTime(formal).toMillis() + 10000));
            assertThat(save(ctx, copy.id()).status()).isEqualTo("CONFLICTED");
            var restored = copies.saveAs(copy.id()); assertThat(restored.status()).isEqualTo("SAVED_AS");
            assertThat(restored.recoveredFileId()).isNotEqualTo(file.getId());
            assertThat(formal).hasContent("original bytes"); assertThat(work).hasContent("edited-conflict");
            var preserved = ctx.getBean(FileMetadataRepository.class).findById(restored.recoveredFileId()).orElseThrow();
            assertThat(preserved.getStatus()).isEqualTo(FileStatus.FAILED);
            assertThat(ctx.getBean(DesktopDataDirectory.class).libraryRoot().resolve(preserved.getStoragePath())).hasContent("edited-conflict");
            verifyNoInteractions(ctx.getBean(AsyncFileProcessor.class));
            assertThat(copies.close(copy.id(), "CLOSE", restored.sha256()).status()).isEqualTo("CLOSED");
        }
    }
    @Test void changedRevisionAndSizeAndDigestAreConflictsAndMissingOriginalCanStillSaveAs() throws Exception {
        try (var ctx = start(true)) {
            owner(ctx, "changed-original"); var copies = ctx.getBean(WorkCopyService.class); var repo = ctx.getBean(FileMetadataRepository.class);
            for (String cause : List.of("revision", "sha", "size", "fileKey")) {
                var file = seed(ctx, cause + ".txt"); var copy = copies.create(file.getId());
                Files.writeString(Path.of(copy.workPath()), "edited-" + cause);
                Path formal = ctx.getBean(DesktopDataDirectory.class).libraryRoot().resolve(file.getStoragePath());
                switch (cause) {
                    case "revision" -> { file.setRevision(4L); repo.saveAndFlush(file); }
                    case "sha" -> Files.writeString(formal, "changed! bytes");
                    case "size" -> Files.writeString(formal, "longer changed original bytes");
                    case "fileKey" -> {
                        var time = Files.getLastModifiedTime(formal); Path replacement = formal.resolveSibling("replacement.tmp");
                        Files.writeString(replacement, "original bytes"); Files.delete(formal); Files.move(replacement, formal); Files.setLastModifiedTime(formal, time);
                        // Windows filename tunnelling can preserve creation time; explicitly change the fallback identity.
                        if (ctx.getBean(FileStoragePort.class).localIdentity(file.getStoragePath()).fileKey().startsWith("created:"))
                            Files.setAttribute(formal, "basic:creationTime", FileTime.fromMillis(System.currentTimeMillis() + 20000));
                    }
                }
                assertThat(save(ctx, copy.id()).status()).as(cause).isEqualTo("CONFLICTED");
                assertThat(Path.of(copy.workPath())).hasContent("edited-" + cause);
            }
            var file = seed(ctx, "missing.txt"); var copy = copies.create(file.getId());
            Files.writeString(Path.of(copy.workPath()), "orphaned edited copy");
            Files.delete(ctx.getBean(DesktopDataDirectory.class).libraryRoot().resolve(file.getStoragePath()));
            assertThat(copies.saveAs(copy.id()).status()).isEqualTo("SAVED_AS");
        }
    }
    @Test void unsavedCloseRequiresDecisionAndCrashReportKeepsDiskCopyUntilExplicitDigestConfirmedDiscard() throws Exception {
        try (var ctx = start(true)) {
            owner(ctx, "unsaved-exit"); var file = seed(ctx, "draft.txt"); var copies = ctx.getBean(WorkCopyService.class);
            var copy = copies.create(file.getId()); Path path = Path.of(copy.workPath()); Files.writeString(path, "disk draft");
            var dirty = copies.get(copy.id()); assertThat(dirty.modified()).isTrue();
            assertThat(copies.close(copy.id(), "CLOSE", dirty.sha256()).status()).isEqualTo("NEEDS_DECISION");
            assertThat(copies.close(copy.id(), "REPORT_EXIT", null).errorCode()).isEqualTo("APP_EXIT_UNCONFIRMED");
            assertThat(copies.close(copy.id(), "KEEP", null).status()).isEqualTo("KEPT");
            Files.writeString(path, "changed after confirmation");
            assertThat(copies.close(copy.id(), "DISCARD", dirty.sha256()).errorCode()).isEqualTo("COPY_CONFIRMATION_CHANGED");
            assertThat(path).hasContent("changed after confirmation");
            assertThat(copies.close(copy.id(), "DISCARD", copies.get(copy.id()).sha256()).status()).isEqualTo("DISCARDED");
            assertThat(path).doesNotExist(); assertThat(ctx.getBean(FileMetadataRepository.class).findById(file.getId()).orElseThrow().getRevision()).isEqualTo(3L);
        }
    }
    @Test void restartKeepsUnclosedCopyAndExposesInterruptedSession() throws Exception {
        long owner; String id; Path path;
        try (var ctx = start(true)) {
            owner = owner(ctx, "restart-work"); var file = seed(ctx, "restart.txt"); var copies = ctx.getBean(WorkCopyService.class);
            var copy = copies.create(file.getId()); copies.open(copy.id()); id = copy.id(); path = Path.of(copy.workPath());
            Files.writeString(path, "saved-to-disk-before-exit");
        }
        TenantContext.clear();
        try (var ctx = start(false)) {
            TenantContext.set(owner); var copies = ctx.getBean(WorkCopyService.class); var recovered = copies.get(id);
            assertThat(recovered.status()).isEqualTo("INTERRUPTED"); assertThat(recovered.errorCode()).isEqualTo("BACKEND_EXIT_UNCONFIRMED");
            assertThat(recovered.modified()).isTrue(); assertThat(path).hasContent("saved-to-disk-before-exit");
            assertThat(copies.saveAs(id).status()).isEqualTo("SAVED_AS");
        }
    }
    @Test void saveAsIntentRecoveryCannotCommitOverAnUnchangedOriginal() throws Exception {
        try (var ctx = start(true)) {
            owner(ctx, "preserve-recovery"); var file = seed(ctx, "original.txt"); var copies = ctx.getBean(WorkCopyService.class);
            var copy = copies.create(file.getId()); Files.writeString(Path.of(copy.workPath()), "edited-copy");
            var service = ctx.getBean(WorkSaveIntentService.class); String id = UUID.randomUUID().toString();
            String target = "users/" + TenantContext.requireOwnerId() + "/managed/files/" + id + ".txt";
            byte[] bytes = "edited-copy".getBytes(); String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            service.preparePreserve(id, file.getId(), file.getFileName(), file.getFileType(), file.getStoragePath(), file.getRevision(), file.getContentSha256(), file.getFileSize(), target, bytes.length, sha, UUID.randomUUID().toString());
            ctx.getBean(FileStoragePort.class).writeVerified(target, new ByteArrayInputStream(bytes), null, bytes.length, sha);
            ctx.getBean(WorkSaveIntentRepository.class).findById(id).ifPresent(intent -> { intent.setNextAttemptAt(null); ctx.getBean(WorkSaveIntentRepository.class).saveAndFlush(intent); });
            ctx.getBean(WorkSaveRecovery.class).reconcile(id);
            assertThat(service.require(id).getStatus()).isEqualTo(WorkSaveStatus.RECOVERED);
            assertThat(ctx.getBean(FileMetadataRepository.class).findById(file.getId()).orElseThrow().getRevision()).isEqualTo(3L);
            verifyNoInteractions(ctx.getBean(AsyncFileProcessor.class));
        }
    }
    @Test void mtimeChangeAfterIntentPublicationStillBlocksCommit() throws Exception {
        try (var ctx = start(true)) {
            owner(ctx, "commit-identity"); var file = seed(ctx, "commit.txt"); var service = ctx.getBean(WorkSaveIntentService.class);
            String id = UUID.randomUUID().toString(), target = "users/" + TenantContext.requireOwnerId() + "/managed/files/" + id + ".txt";
            byte[] bytes = "new-version".getBytes(); String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            service.prepare(id, file, file.getRevision(), file.getContentSha256(), target, bytes.length, sha, UUID.randomUUID().toString(), "fixture", GovernanceRunMode.LOCAL);
            service.objectWritten(id, ctx.getBean(FileStoragePort.class).writeVerified(target, new ByteArrayInputStream(bytes), null, bytes.length, sha));
            Path formal = ctx.getBean(DesktopDataDirectory.class).libraryRoot().resolve(file.getStoragePath());
            Files.setLastModifiedTime(formal, FileTime.fromMillis(Files.getLastModifiedTime(formal).toMillis() + 10000));
            assertThat(service.commit(id)).isFalse(); assertThat(service.require(id).getStatus()).isEqualTo(WorkSaveStatus.MANUAL_REVIEW);
            assertThat(formal).hasContent("original bytes");
            assertThat(ctx.getBean(DesktopDataDirectory.class).libraryRoot().resolve(target)).hasContent("new-version");
            assertThat(ctx.getBean(FileMetadataRepository.class).findById(file.getId()).orElseThrow().getRevision()).isEqualTo(3L);
        }
    }
}
