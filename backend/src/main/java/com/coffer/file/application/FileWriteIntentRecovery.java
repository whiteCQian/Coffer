package com.coffer.file.application;

import com.coffer.auth.service.TenantJobRunner;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileWriteIntent;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.file.storage.StorageObjectNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reconciles committed intent, object and metadata after crashes or lost events. */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileWriteIntentRecovery {
    private final TenantJobRunner owners;
    private final FileWriteIntentService intents;
    private final FileMetadataRepository files;
    private final FileStoragePort storage;
    private final com.coffer.file.infrastructure.persistence.FileWriteIntentRepository intentRows;
    private final com.coffer.file.infrastructure.persistence.WorkSaveIntentRepository workSaveRows;
    private final com.coffer.file.infrastructure.persistence.StorageDeletionTaskRepository deletions;
    private final com.coffer.governance.infrastructure.persistence.ArchiveOperationItemRepository archiveItems;

    @EventListener(ApplicationReadyEvent.class)
    public void atStartup() {
        owners.runForEnabledOwners(ownerId -> { scan(true); inventory(); discardDue(); });
    }

    @com.coffer.auth.service.OwnerScheduled
    @Scheduled(fixedDelayString = "${coffer.storage.write-recovery-delay-ms:30000}")
    public void periodicScan() { scan(false); }

    @com.coffer.auth.service.OwnerScheduled
    @Scheduled(fixedDelayString = "${coffer.storage.inventory-delay-ms:600000}")
    public void periodicInventory() { inventory(); }

    @com.coffer.auth.service.OwnerScheduled
    @Scheduled(fixedDelayString = "${coffer.storage.orphan-discard-scan-delay-ms:30000}")
    public void periodicDiscard() { discardDue(); }

    private void inventory() {
        try {
            for (String key : storage.listOwnedKeys()) {
                if (intentRows.existsByObjectKeyAndStatusIn(key, FileWriteIntentService.ACTIVE_KEY_STATES)
                        || hasExternalReference(key)) continue;
                try { intents.recordUnknownOrphan(storage.stat(key)); }
                catch (RuntimeException failure) {
                    log.warn("未知存储对象待下轮对账 exceptionType={}", failure.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException unavailable) {
            log.warn("存储对象清单暂不可用 exceptionType={}", unavailable.getClass().getSimpleName());
        }
        try {
            for (FileStoragePort.VersionIssue issue : storage.listOwnedVersionIssues()) {
                try { intents.recordVersionIssue(issue); }
                catch (RuntimeException failure) {
                    log.warn("历史对象版本异常待下轮登记 exceptionType={}", failure.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException unavailable) {
            log.warn("历史对象版本清单暂不可用 exceptionType={}", unavailable.getClass().getSimpleName());
        }
    }

    private void scan(boolean startup) {
        for (String id : intents.dueIds(startup)) {
            try { reconcile(id); }
            catch (RuntimeException failure) {
                try { intents.recoveryFailed(id, failure); }
                catch (RuntimeException trackingFailure) {
                    log.error("文件写入对账失败且无法记录重试状态 operationId={} exceptionType={}",
                            id, trackingFailure.getClass().getSimpleName());
                }
                log.warn("文件写入意图对账稍后重试 operationId={} exceptionType={}",
                        id, failure.getClass().getSimpleName());
            }
        }
    }

    public void reconcile(String id) {
        FileWriteIntent intent = intents.require(id);
        if ("VERSION_ANOMALY".equals(intent.getKind())) {
            var currentIssue = storage.listOwnedVersionIssues().stream()
                    .filter(issue -> issue.key().equals(intent.getObjectKey())).findFirst();
            if (currentIssue.isPresent()) {
                intents.recordVersionIssue(currentIssue.orElseThrow());
                throw new com.coffer.file.storage.StorageConflictException("对象历史版本异常仍存在，需人工核对");
            }
            intents.resolveVersionIssue(id);
            return;
        }
        if (intent.getStatus() == com.coffer.file.domain.FileWriteIntentStatus.DISCARD_PENDING
                || intent.getStatus() == com.coffer.file.domain.FileWriteIntentStatus.DISCARDING
                || intent.getStatus() == com.coffer.file.domain.FileWriteIntentStatus.DISCARDED) return;
        FileMetadata file = files.findByTaskId(intent.getTaskId()).orElse(null);
        FileStoragePort.StoredObject object;
        try { object = storage.stat(intent.getObjectKey()); }
        catch (StorageObjectNotFoundException missing) {
            if (file != null) intents.missingContent(id);
            else intents.aborted(id, "WRITE_NOT_PUBLISHED");
            return;
        }
        if (object.size() != intent.getDeclaredSize()
                || (intent.getContentSha256() != null
                    && !intent.getContentSha256().equalsIgnoreCase(object.sha256()))) {
            intents.manual(id, "OBJECT_IDENTITY_CONFLICT", object.sha256());
            return;
        }
        if (file == null) {
            // Quarantine logically: no file ID can stream this object. Keep bytes for a
            // reviewed recovery instead of deleting user data after a failed commit.
            intents.manual(id, "ORPHAN_OBJECT", object.sha256());
            return;
        }
        if (!Objects.equals(file.getStoragePath(), intent.getObjectKey())
                || !Objects.equals(file.getContentSha256(), object.sha256())
                || !Objects.equals(file.getFileSize(), object.size())) {
            intents.manual(id, "METADATA_IDENTITY_CONFLICT", object.sha256());
            return;
        }
        intents.objectWritten(id, object);
        intents.registered(id);
    }

    public Long attachOrphan(String id) {
        FileWriteIntent intent = intents.require(id);
        FileStoragePort.StoredObject object = storage.stat(intent.getObjectKey());
        return intents.attachForManualReview(id, object);
    }

    public void requestOrphanDiscard(String id, String sha256) {
        FileWriteIntent intent = intents.require(id);
        if (hasExternalReference(intent.getObjectKey()))
            throw new IllegalStateException("对象已有文件或处理任务引用，不能放弃");
        FileStoragePort.StoredObject object = storage.stat(intent.getObjectKey());
        intents.requestDiscard(id, object, sha256);
    }

    private void discardDue() {
        for (String id : intents.dueDiscardIds()) discard(id);
    }

    public void discard(String id) {
        FileWriteIntent previous = intents.require(id);
        // A process may have died after the physical delete but before recording success.
        // At the retry limit, verify that exact outcome without attempting a third delete.
        if (previous.getStatus() == com.coffer.file.domain.FileWriteIntentStatus.DISCARDING
                && intents.discardAttemptsExhausted(previous)) {
            try { storage.stat(previous.getObjectKey()); }
            catch (StorageObjectNotFoundException alreadyRemoved) {
                intents.discarded(id);
                return;
            }
            catch (RuntimeException unavailable) {
                intents.discardBlocked(id, "DISCARD_STATE_UNVERIFIED");
                log.warn("孤儿对象最终删除状态需人工核对 operationId={} exceptionType={}",
                        id, unavailable.getClass().getSimpleName());
                return;
            }
        }
        FileWriteIntent intent = intents.claimDiscard(id);
        if (intent == null) return;
        try {
            if (hasExternalReference(intent.getObjectKey())) {
                intents.discardBlocked(id, "OBJECT_REFERENCE_CONFLICT");
                return;
            }
            FileStoragePort.StoredObject object = storage.stat(intent.getObjectKey());
            if (object.size() != intent.getDeclaredSize()
                    || !Objects.equals(object.sha256(), intent.getContentSha256())) {
                intents.discardBlocked(id, "OBJECT_IDENTITY_CONFLICT");
                return;
            }
            storage.delete(intent.getObjectKey(), intent.getContentSha256());
            intents.discarded(id);
        } catch (StorageObjectNotFoundException alreadyRemoved) {
            intents.discarded(id);
        } catch (RuntimeException failure) {
            intents.discardFailed(id, failure);
            log.warn("孤儿对象清理稍后重试 operationId={} exceptionType={}",
                    id, failure.getClass().getSimpleName());
        }
    }

    private boolean hasExternalReference(String key) {
        return files.existsByStoragePath(key)
                || workSaveRows.existsByTargetKey(key)
                || deletions.existsByObjectPathAndStatusNot(key,
                        com.coffer.file.domain.StorageDeletionStatus.SUCCEEDED)
                || archiveItems.existsActivePath(key,
                        java.util.List.of(
                                com.coffer.governance.domain.ArchiveOperationItemExecutionStatus.PENDING,
                                com.coffer.governance.domain.ArchiveOperationItemExecutionStatus.VALIDATING,
                                com.coffer.governance.domain.ArchiveOperationItemExecutionStatus.COPYING,
                                com.coffer.governance.domain.ArchiveOperationItemExecutionStatus.DB_COMMITTING,
                                com.coffer.governance.domain.ArchiveOperationItemExecutionStatus.CLEANUP_PENDING,
                                com.coffer.governance.domain.ArchiveOperationItemExecutionStatus.FAILED,
                                com.coffer.governance.domain.ArchiveOperationItemExecutionStatus.MANUAL_REVIEW),
                        java.util.List.of(
                                com.coffer.governance.domain.ArchiveOperationItemRollbackStatus.PENDING,
                                com.coffer.governance.domain.ArchiveOperationItemRollbackStatus.VALIDATING,
                                com.coffer.governance.domain.ArchiveOperationItemRollbackStatus.COPYING,
                                com.coffer.governance.domain.ArchiveOperationItemRollbackStatus.DB_COMMITTING,
                                com.coffer.governance.domain.ArchiveOperationItemRollbackStatus.CLEANUP_PENDING,
                                com.coffer.governance.domain.ArchiveOperationItemRollbackStatus.FAILED));
    }
}
