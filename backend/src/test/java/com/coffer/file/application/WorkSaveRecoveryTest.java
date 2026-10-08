package com.coffer.file.application;

import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.file.domain.WorkSaveStatus;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.infrastructure.persistence.StorageDeletionTaskRepository;
import com.coffer.file.infrastructure.persistence.WorkSaveIntentRepository;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.service.MinioStorageService;
import com.coffer.task.infrastructure.persistence.AsyncTaskRepository;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.model.runtime.ModelExecutionContext;
import com.coffer.auth.service.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:work_save_recovery;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.flyway.locations=classpath:db/migration/h2",
        "minio.access-key=test-access-key", "minio.secret-key=test-secret-key"
})
class WorkSaveRecoveryTest extends com.coffer.auth.OwnerTestSupport {
    private static final String OLD_SHA = "a".repeat(64);
    private static final String NEW_SHA = "b".repeat(64);
    @Autowired WorkSaveIntentService intents;
    @Autowired WorkSaveRecovery recovery;
    @Autowired WorkSaveApplicationService application;
    @Autowired WorkSaveIntentRepository rows;
    @Autowired FileMetadataRepository files;
    @Autowired AsyncTaskRepository tasks;
    @Autowired StorageDeletionTaskRepository deletions;
    @MockitoBean MinioStorageService storage;
    @MockitoBean com.coffer.file.application.async.AsyncFileProcessor processor;

    @BeforeEach void clear() {
        rows.deleteAll();
        deletions.deleteAll();
        files.deleteAll();
        tasks.deleteAll();
    }

    @Test void restartAfterObjectWriteCommitsNewVersionAndRetainsOldObjectForDelayedCleanup() {
        FileMetadata original = original();
        String operationId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        String target = ownerPath("files/work/new.txt");
        intents.prepare(operationId, original, 4L, OLD_SHA, target, 9, NEW_SHA, taskId, "snapshot", GovernanceRunMode.API);
        when(storage.stat(target)).thenReturn(new FileStoragePort.StoredObject(target, 9, NEW_SHA, "new-etag"));
        when(storage.stat(original.getStoragePath())).thenReturn(new FileStoragePort.StoredObject(
                original.getStoragePath(), 8, OLD_SHA, "old-etag"));

        // The object exists, but no objectWritten/SQL commit was recorded before restart.
        recovery.reconcile(operationId, true);

        var saved = files.findById(original.getId()).orElseThrow();
        assertThat(saved.getStoragePath()).isEqualTo(target);
        assertThat(saved.getRevision()).isEqualTo(5L);
        assertThat(saved.getContentSha256()).isEqualTo(NEW_SHA);
        assertThat(saved.getStatus()).isEqualTo(FileStatus.PENDING);
        assertThat(tasks.findByTaskId(taskId)).isPresent();
        assertThat(rows.findById(operationId).orElseThrow().getStatus()).isEqualTo(WorkSaveStatus.COMMITTED);
        assertThat(deletions.findAll()).singleElement().satisfies(task -> {
            assertThat(task.getObjectPath()).isEqualTo(original.getStoragePath());
            assertThat(task.getRetentionUntil()).isNotNull();
        });
        recovery.reconcile(operationId);
        assertThat(deletions.findAll()).hasSize(1);
    }

    @Test void changedFormalRevisionNeverGetsOverwrittenAndEditedBytesCanBeRestored() {
        FileMetadata original = original();
        String operationId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        String target = ownerPath("files/work/conflict.txt");
        intents.prepare(operationId, original, 4L, OLD_SHA, target, 9, NEW_SHA, taskId, "snapshot", GovernanceRunMode.API);
        intents.objectWritten(operationId, new FileStoragePort.StoredObject(target, 9, NEW_SHA, "new-etag"));
        original.setRevision(5L);
        files.saveAndFlush(original);
        when(storage.stat(target)).thenReturn(new FileStoragePort.StoredObject(target, 9, NEW_SHA, "new-etag"));

        assertThat(intents.commit(operationId)).isFalse();
        assertThat(files.findById(original.getId()).orElseThrow().getStoragePath())
                .isEqualTo(original.getStoragePath());
        assertThat(rows.findById(operationId).orElseThrow().getStatus()).isEqualTo(WorkSaveStatus.CONFLICTED);

        Long restoredId = intents.restoreAsNewFile(operationId);
        assertThat(restoredId).isNotEqualTo(original.getId());
        assertThat(files.findById(restoredId).orElseThrow().getStatus()).isEqualTo(FileStatus.FAILED);
        assertThat(files.findById(restoredId).orElseThrow().getStoragePath()).isEqualTo(target);
        assertThat(intents.restoreAsNewFile(operationId)).isEqualTo(restoredId);
    }

    @Test void missingUnpublishedCopyAbortsWithoutChangingFormalFile() {
        FileMetadata original = original();
        String id = UUID.randomUUID().toString();
        String target = ownerPath("files/work/missing.txt");
        intents.prepare(id, original, 4L, OLD_SHA, target, 9, NEW_SHA, UUID.randomUUID().toString(),
                "snapshot", GovernanceRunMode.API);
        when(storage.stat(target)).thenThrow(new com.coffer.file.storage.StorageObjectNotFoundException());

        recovery.reconcile(id); // an in-flight HTTP write must not be aborted by the periodic scan
        assertThat(rows.findById(id).orElseThrow().getStatus()).isEqualTo(WorkSaveStatus.PREPARED);
        recovery.reconcile(id, true);

        assertThat(rows.findById(id).orElseThrow().getStatus()).isEqualTo(WorkSaveStatus.ABORTED);
        assertThat(files.findById(original.getId()).orElseThrow().getStoragePath())
                .isEqualTo(original.getStoragePath());
    }

    @Test void startupHonorsBackoffAfterWorkSaveReconciliationFailure() {
        FileMetadata original = original();
        String id = UUID.randomUUID().toString();
        intents.prepare(id, original, 4L, OLD_SHA, ownerPath("files/work/retry.txt"), 9,
                NEW_SHA, UUID.randomUUID().toString(), "snapshot", GovernanceRunMode.API);
        assertThat(intents.dueIds(true)).contains(id);

        intents.failure(id, new IllegalStateException("storage unavailable"));
        assertThat(intents.dueIds(true)).doesNotContain(id);
        recovery.reconcile(id, true);
        assertThat(rows.findById(id).orElseThrow().getStatus()).isEqualTo(WorkSaveStatus.PREPARED);

        var due = rows.findById(id).orElseThrow();
        due.setNextAttemptAt(java.time.LocalDateTime.now().minusSeconds(1));
        rows.saveAndFlush(due);
        assertThat(intents.dueIds(true)).contains(id);
    }

    @Test void savingEditedBytesUsesTheSameDurableCommitPath() throws Exception {
        FileMetadata original = original();
        String editedSha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("new bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(storage.stat(anyString())).thenAnswer(call -> {
            String key = call.getArgument(0);
            return key.equals(original.getStoragePath())
                    ? new FileStoragePort.StoredObject(key, 8, OLD_SHA, "old-etag")
                    : new FileStoragePort.StoredObject(key, 9, editedSha, "new-etag");
        });
        when(storage.write(anyString(), any(), eq("text/plain"), eq(9L)))
                .thenAnswer(call -> new FileStoragePort.StoredObject(call.getArgument(0), 9, editedSha, "new-etag"));
        var edited = new MockMultipartFile("file", "edited.txt", "text/plain", "new bytes".getBytes());
        var model = new ModelExecutionContext.Snapshot("snapshot", TenantContext.requireOwnerId(),
                "v1", GovernanceRunMode.API, Map.of());
        String requestId = UUID.randomUUID().toString();

        var result = ModelExecutionContext.with(model,
                () -> application.save(original.getId(), 4L, OLD_SHA, requestId, edited));
        var replay = ModelExecutionContext.with(model,
                () -> application.save(original.getId(), 4L, OLD_SHA, requestId, edited));

        assertThat(result.revision()).isEqualTo(5L);
        assertThat(replay).isEqualTo(result);
        var different = new MockMultipartFile("file", "edited.txt", "text/plain", "bad bytes".getBytes());
        assertThatThrownBy(() -> ModelExecutionContext.with(model,
                () -> application.save(original.getId(), 4L, OLD_SHA, requestId, different)))
                .isInstanceOf(com.coffer.file.storage.StorageConflictException.class);
        verify(storage, times(1)).write(anyString(), any(), eq("text/plain"), eq(9L));
        assertThat(rows.findById(result.operationId()).orElseThrow().getStatus()).isEqualTo(WorkSaveStatus.COMMITTED);
        assertThat(files.findById(original.getId()).orElseThrow().getContentSha256()).isEqualTo(editedSha);
    }

    private FileMetadata original() {
        return files.saveAndFlush(FileMetadata.builder().fileName("edited.txt")
                .fileType("txt").fileSize(8L).storagePath(ownerPath("files/work/original.txt"))
                .contentSha256(OLD_SHA).revision(4L).status(FileStatus.COMPLETED).build());
    }
}
