package com.coffer.file.application;

import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.file.domain.FileWriteIntentStatus;
import com.coffer.file.domain.FileWriteIntent;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.infrastructure.persistence.FileWriteIntentRepository;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.file.storage.StorageObjectNotFoundException;
import com.coffer.service.MinioStorageService;
import com.coffer.task.domain.AsyncTask;
import com.coffer.task.domain.AsyncTaskStatus;
import com.coffer.task.infrastructure.persistence.AsyncTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:file_write_recovery;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.flyway.locations=classpath:db/migration/h2",
        "minio.access-key=test-access-key", "minio.secret-key=test-secret-key"
})
class FileWriteIntentRecoveryTest extends com.coffer.auth.OwnerTestSupport {
    @Autowired FileWriteIntentService intents;
    @Autowired FileWriteIntentRecovery recovery;
    @Autowired FileWriteIntentRepository intentRows;
    @Autowired FileMetadataRepository files;
    @Autowired AsyncTaskRepository tasks;
    @Autowired com.coffer.auth.infrastructure.AppUserRepository users;
    @MockitoBean MinioStorageService storage;

    @BeforeEach void clearRows() {
        intentRows.deleteAll();
        files.deleteAll();
        tasks.deleteAll();
    }

    @Test void orphanAfterDatabaseRollbackRemainsPrivateAndCanBeRestoredWithoutModelCall() {
        String id = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        String key = ownerPath("files/recovery/original.txt");
        FileStoragePort.StoredObject object = new FileStoragePort.StoredObject(key, 7, "a".repeat(64), "etag");
        intents.begin(id, taskId, "UPLOAD", key, "original.txt", "txt", "text/plain", 7, null);
        when(storage.stat(key)).thenReturn(object);

        recovery.reconcile(id);
        assertThat(intentRows.findById(id).orElseThrow().getStatus()).isEqualTo(FileWriteIntentStatus.MANUAL_REVIEW);
        assertThat(files.findByTaskId(taskId)).isEmpty();
        assertThat(intentRows.findById(id).orElseThrow().getLastErrorCode()).isEqualTo("ORPHAN_OBJECT");

        Long fileId = recovery.attachOrphan(id);
        FileMetadata recovered = files.findById(fileId).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(FileStatus.FAILED);
        assertThat(recovered.getStoragePath()).isEqualTo(key);
        assertThat(recovered.getContentSha256()).isEqualTo(object.sha256());
        assertThat(tasks.findByTaskId(taskId).orElseThrow().getStatus()).isEqualTo(AsyncTaskStatus.FAILED);
        assertThat(intentRows.findById(id).orElseThrow().getStatus()).isEqualTo(FileWriteIntentStatus.REGISTERED);
        verify(storage, never()).delete(key, object.sha256());
    }

    @Test void missingObjectWithCommittedMetadataBecomesVisibleFailure() {
        String id = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        String key = ownerPath("files/recovery/missing.txt");
        intents.begin(id, taskId, "IMPORT", key, "missing.txt", "txt", "text/plain", 7, null);
        intents.objectWritten(id, new FileStoragePort.StoredObject(key, 7, "b".repeat(64), "etag"));
        files.saveAndFlush(FileMetadata.builder().fileName("missing.txt").fileType("txt")
                .fileSize(7L).storagePath(key).contentSha256("b".repeat(64))
                .taskId(taskId).status(FileStatus.PENDING).build());
        tasks.saveAndFlush(AsyncTask.builder().taskId(taskId).fileName("missing.txt")
                .status(AsyncTaskStatus.PENDING).build());
        when(storage.stat(key)).thenThrow(new StorageObjectNotFoundException());

        recovery.reconcile(id);
        assertThat(files.findByTaskId(taskId).orElseThrow().getStatus()).isEqualTo(FileStatus.FAILED);
        assertThat(tasks.findByTaskId(taskId).orElseThrow().getStatus()).isEqualTo(AsyncTaskStatus.FAILED);
        assertThat(intentRows.findById(id).orElseThrow().getLastErrorCode()).isEqualTo("MISSING_CONTENT");
    }

    @Test void inventoryRecordsUntrackedPublishedObjectOnceForManualReview() {
        String key = ownerPath("files/untracked.txt");
        FileStoragePort.StoredObject object = new FileStoragePort.StoredObject(key, 9, "c".repeat(64), "etag");
        when(storage.listOwnedKeys()).thenReturn(java.util.List.of(key));
        when(storage.stat(key)).thenReturn(object);
        recovery.periodicInventory();
        recovery.periodicInventory();
        var rows = intentRows.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getKind()).isEqualTo("ORPHAN");
        assertThat(rows.get(0).getStatus()).isEqualTo(FileWriteIntentStatus.MANUAL_REVIEW);
        assertThat(rows.get(0).getContentSha256()).isEqualTo(object.sha256());
        assertThat(files.existsByStoragePath(key)).isFalse();
        assertThat(rows.get(0).getRetentionUntil()).isAfter(rows.get(0).getCreatedAt());
    }

    @Test void hiddenVersionIssueIsDurableAndResolvesOnlyAfterARescan() {
        String key = ownerPath("files/hidden.txt");
        var issue = new FileStoragePort.VersionIssue(key, "DELETE_MARKER");
        when(storage.listOwnedVersionIssues()).thenReturn(java.util.List.of(issue),
                java.util.List.of(issue), java.util.List.of(issue), java.util.List.of());

        recovery.periodicInventory();
        recovery.periodicInventory();
        var rows = intentRows.findAll();
        assertThat(rows).hasSize(1);
        FileWriteIntent row = rows.get(0);
        assertThat(row.getKind()).isEqualTo("VERSION_ANOMALY");
        assertThat(row.getStatus()).isEqualTo(FileWriteIntentStatus.MANUAL_REVIEW);
        assertThat(row.getLastErrorCode()).isEqualTo("DELETE_MARKER");
        assertThat(intents.recent()).anyMatch(view -> view.id().equals(row.getId()));
        var anotherOwner = users.saveAndFlush(new com.coffer.auth.domain.AppUser(
                "version-issue-other-" + UUID.randomUUID(), "unused",
                com.coffer.auth.domain.AuthRole.USER));
        com.coffer.auth.service.TenantContext.runAs(anotherOwner.getId(), () ->
                assertThat(intents.recent()).noneMatch(view -> view.id().equals(row.getId())));

        assertThatThrownBy(() -> recovery.reconcile(row.getId()))
                .isInstanceOf(com.coffer.file.storage.StorageConflictException.class);
        assertThat(intentRows.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(FileWriteIntentStatus.MANUAL_REVIEW);
        recovery.reconcile(row.getId());
        assertThat(intentRows.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(FileWriteIntentStatus.RESOLVED);
        assertThat(intents.recent()).anyMatch(view -> view.id().equals(row.getId())
                && view.status() == FileWriteIntentStatus.RESOLVED);
        recovery.reconcile(row.getId());
        when(storage.listOwnedVersionIssues()).thenReturn(java.util.List.of(issue));
        recovery.periodicInventory();
        assertThat(intentRows.findAll()).hasSize(2);
        assertThat(intentRows.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(FileWriteIntentStatus.RESOLVED);
    }

    @Test void confirmedOrphanWaitsForRetentionThenDeletesWithPersistentAudit() {
        String key = ownerPath("files/untracked-discard.txt");
        FileStoragePort.StoredObject object = new FileStoragePort.StoredObject(key, 9, "d".repeat(64), "etag");
        when(storage.stat(key)).thenReturn(object);
        intents.recordUnknownOrphan(object);
        String id = intentRows.findAll().get(0).getId();
        assertThatThrownBy(() -> recovery.requestOrphanDiscard(id, "e".repeat(64)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(intentRows.findById(id).orElseThrow().getStatus()).isEqualTo(FileWriteIntentStatus.MANUAL_REVIEW);

        recovery.requestOrphanDiscard(id, object.sha256());
        assertThat(intentRows.findById(id).orElseThrow().getStatus()).isEqualTo(FileWriteIntentStatus.DISCARD_PENDING);
        assertThatThrownBy(() -> recovery.attachOrphan(id)).isInstanceOf(IllegalStateException.class);
        recovery.discard(id);
        verify(storage, never()).delete(key, object.sha256());

        FileWriteIntent row = intentRows.findById(id).orElseThrow();
        row.setRetentionUntil(LocalDateTime.now().minusMinutes(1));
        row.setNextAttemptAt(LocalDateTime.now().minusMinutes(1));
        intentRows.saveAndFlush(row);
        recovery.discard(id);
        verify(storage).delete(key, object.sha256());
        assertThat(intentRows.findById(id).orElseThrow().getStatus()).isEqualTo(FileWriteIntentStatus.DISCARDED);
        assertThat(intentRows.findById(id).orElseThrow().getDiscardedAt()).isNotNull();
        assertThat(intents.recent().stream().anyMatch(view -> view.id().equals(id)
                && view.discardedAt() != null)).isTrue();
    }

    @Test void changedOrphanIsNeverDeleted() {
        String key = ownerPath("files/untracked-conflict.txt");
        FileStoragePort.StoredObject original = new FileStoragePort.StoredObject(key, 9, "f".repeat(64), "etag");
        when(storage.stat(key)).thenReturn(original);
        intents.recordUnknownOrphan(original);
        String id = intentRows.findAll().get(0).getId();
        recovery.requestOrphanDiscard(id, original.sha256());
        FileWriteIntent row = intentRows.findById(id).orElseThrow();
        row.setRetentionUntil(LocalDateTime.now().minusMinutes(1));
        row.setNextAttemptAt(LocalDateTime.now().minusMinutes(1));
        intentRows.saveAndFlush(row);
        when(storage.stat(key)).thenReturn(new FileStoragePort.StoredObject(key, 9, "a".repeat(64), "etag"));
        recovery.discard(id);
        assertThat(intentRows.findById(id).orElseThrow().getLastErrorCode())
                .isEqualTo("OBJECT_IDENTITY_CONFLICT");
        verify(storage, never()).delete(key, original.sha256());
    }

    @Test void newlyReferencedOrphanIsNeverDeleted() {
        String key = ownerPath("files/untracked-now-referenced.txt");
        FileStoragePort.StoredObject object = new FileStoragePort.StoredObject(key, 9, "a".repeat(64), "etag");
        when(storage.stat(key)).thenReturn(object);
        intents.recordUnknownOrphan(object);
        String id = intentRows.findAll().get(0).getId();
        recovery.requestOrphanDiscard(id, object.sha256());
        FileWriteIntent row = intentRows.findById(id).orElseThrow();
        row.setRetentionUntil(LocalDateTime.now().minusMinutes(1));
        row.setNextAttemptAt(LocalDateTime.now().minusMinutes(1));
        intentRows.saveAndFlush(row);
        files.saveAndFlush(FileMetadata.builder().fileName("now-referenced.txt").fileType("txt")
                .fileSize(9L).storagePath(key).contentSha256(object.sha256())
                .taskId(UUID.randomUUID().toString()).status(FileStatus.FAILED).build());
        recovery.discard(id);
        assertThat(intentRows.findById(id).orElseThrow().getLastErrorCode())
                .isEqualTo("OBJECT_REFERENCE_CONFLICT");
        verify(storage, never()).delete(key, object.sha256());
    }

    @Test void orphanDiscardStopsAfterSecondFailure() {
        String key = ownerPath("files/untracked-retry.txt");
        FileStoragePort.StoredObject object = new FileStoragePort.StoredObject(key, 9, "b".repeat(64), "etag");
        when(storage.stat(key)).thenReturn(object);
        intents.recordUnknownOrphan(object);
        String id = intentRows.findAll().get(0).getId();
        recovery.requestOrphanDiscard(id, object.sha256());
        doThrow(new IllegalStateException("offline")).when(storage).delete(key, object.sha256());
        for (int attempt = 0; attempt < 2; attempt++) {
            FileWriteIntent row = intentRows.findById(id).orElseThrow();
            row.setRetentionUntil(LocalDateTime.now().minusMinutes(1));
            row.setNextAttemptAt(LocalDateTime.now().minusMinutes(1));
            intentRows.saveAndFlush(row);
            recovery.discard(id);
        }
        assertThat(intentRows.findById(id).orElseThrow().getStatus()).isEqualTo(FileWriteIntentStatus.MANUAL_REVIEW);
        assertThat(intentRows.findById(id).orElseThrow().getAttempts()).isEqualTo(2);
        assertThat(intents.dueDiscardIds()).doesNotContain(id);
    }

    @Test void crashAfterSecondPhysicalDeleteCanStillRecordCompletion() {
        String key = ownerPath("files/discarded-before-crash.txt");
        FileStoragePort.StoredObject object = new FileStoragePort.StoredObject(key, 9, "c".repeat(64), "etag");
        when(storage.stat(key)).thenReturn(object);
        intents.recordUnknownOrphan(object);
        String id = intentRows.findAll().get(0).getId();
        recovery.requestOrphanDiscard(id, object.sha256());
        FileWriteIntent row = intentRows.findById(id).orElseThrow();
        row.setStatus(FileWriteIntentStatus.DISCARDING);
        row.setAttempts(2);
        row.setRetentionUntil(LocalDateTime.now().minusMinutes(1));
        row.setNextAttemptAt(LocalDateTime.now().minusMinutes(1));
        intentRows.saveAndFlush(row);
        when(storage.stat(key)).thenThrow(new StorageObjectNotFoundException());
        recovery.discard(id);
        assertThat(intentRows.findById(id).orElseThrow().getStatus()).isEqualTo(FileWriteIntentStatus.DISCARDED);
        verify(storage, never()).delete(key, object.sha256());
    }

    @Test void objectReappearingAtAPreviouslyDiscardedKeyGetsANewReviewRecord() {
        String key = ownerPath("files/reappeared.txt");
        FileStoragePort.StoredObject first = new FileStoragePort.StoredObject(key, 9, "d".repeat(64), "etag-1");
        when(storage.stat(key)).thenReturn(first);
        intents.recordUnknownOrphan(first);
        String firstId = intentRows.findAll().get(0).getId();
        recovery.requestOrphanDiscard(firstId, first.sha256());
        FileWriteIntent row = intentRows.findById(firstId).orElseThrow();
        row.setRetentionUntil(LocalDateTime.now().minusMinutes(1));
        row.setNextAttemptAt(LocalDateTime.now().minusMinutes(1));
        intentRows.saveAndFlush(row);
        recovery.discard(firstId);

        FileStoragePort.StoredObject later = new FileStoragePort.StoredObject(key, 11, "e".repeat(64), "etag-2");
        when(storage.listOwnedKeys()).thenReturn(java.util.List.of(key));
        when(storage.stat(key)).thenReturn(later);
        recovery.periodicInventory();
        assertThat(intentRows.findAll()).hasSize(2);
        assertThat(intentRows.findById(firstId).orElseThrow().getStatus())
                .isEqualTo(FileWriteIntentStatus.DISCARDED);
        assertThat(intentRows.findAll().stream().filter(i -> !i.getId().equals(firstId))
                .map(FileWriteIntent::getStatus)).containsExactly(FileWriteIntentStatus.MANUAL_REVIEW);
    }

    @Test void failureLedgerCanReachRecordsOlderThanTheFirstHundred() {
        for (int index = 0; index < 101; index++) {
            FileWriteIntent row = new FileWriteIntent();
            row.setId(UUID.randomUUID().toString());
            row.setTaskId(UUID.randomUUID().toString());
            row.setKind("UPLOAD");
            row.setObjectKey(ownerPath("files/history/" + index + ".txt"));
            row.setFileName("historical-" + index + ".txt");
            row.setDeclaredSize(1);
            row.setStatus(FileWriteIntentStatus.MANUAL_REVIEW);
            intentRows.save(row);
        }
        intentRows.flush();

        var first = intents.recent(0);
        var second = intents.recent(1);
        assertThat(first).hasSize(100);
        assertThat(second).hasSize(1);
        assertThat(first).extracting(FileWriteIntentService.IntentView::id)
                .doesNotContain(second.get(0).id());
    }

    @Test void repeatedReconciliationFailuresStopAtReviewState() {
        String id = UUID.randomUUID().toString();
        intents.begin(id, UUID.randomUUID().toString(), "UPLOAD",
                ownerPath("files/recovery/retry.txt"), "retry.txt", "txt", "text/plain", 4, null);
        intents.recoveryFailed(id, new IllegalStateException("storage unavailable"));
        assertThat(intentRows.findById(id).orElseThrow().getStatus()).isEqualTo(FileWriteIntentStatus.FAILED);
        intents.recoveryFailed(id, new IllegalStateException("storage unavailable"));
        var intent = intentRows.findById(id).orElseThrow();
        assertThat(intent.getStatus()).isEqualTo(FileWriteIntentStatus.MANUAL_REVIEW);
        assertThat(intent.getAttempts()).isEqualTo(2);
        assertThat(intent.getNextAttemptAt()).isNull();
        assertThat(intents.dueIds(false)).doesNotContain(id);
    }

    @Test void startupRecoversInterruptedWritesButHonorsFailureBackoff() {
        String id = UUID.randomUUID().toString();
        intents.begin(id, UUID.randomUUID().toString(), "UPLOAD",
                ownerPath("files/recovery/startup.txt"), "startup.txt", "txt", "text/plain", 4, null);
        // A new JVM may immediately inspect an interrupted write even if its old lease is unexpired.
        assertThat(intents.dueIds(true)).contains(id);

        intents.recoveryFailed(id, new IllegalStateException("storage unavailable"));
        assertThat(intents.dueIds(true)).doesNotContain(id);
        FileWriteIntent retry = intentRows.findById(id).orElseThrow();
        retry.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        intentRows.saveAndFlush(retry);
        assertThat(intents.dueIds(true)).contains(id);
    }
}
