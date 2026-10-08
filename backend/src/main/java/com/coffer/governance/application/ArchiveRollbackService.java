package com.coffer.governance.application;

import com.coffer.file.domain.FileMetadata;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.file.storage.StorageObjectNotFoundException;
import com.coffer.governance.domain.ArchiveOperationItem;
import com.coffer.governance.domain.ArchiveFormalSnapshot;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Objects;

/** Safely restores archived files without overwriting paths or changed metadata. */
@Slf4j
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class ArchiveRollbackService {
    private final ArchiveRollbackPersistenceService persistenceService;
    private final ArchiveOperationItemRepository itemRepository;
    private final FileMetadataRepository fileMetadataRepository;
    private final FileStoragePort storage;
    private final ArchiveSnapshotService snapshotService;
    private final ApplicationEventPublisher eventPublisher;
    private final GovernanceCompensationRegistry compensationRegistry;

    @Transactional
    public void requestBatch(String batchId) {
        if (persistenceService.prepareBatch(batchId)) {
            eventPublisher.publishEvent(new ArchiveRollbackRequested(batchId, null));
        }
    }

    @Transactional
    public void requestItem(String batchId, Long itemId) {
        if (persistenceService.prepareItem(batchId, itemId)) {
            eventPublisher.publishEvent(new ArchiveRollbackRequested(batchId, itemId));
        }
    }

    @Async("taskExecutor")
    @com.coffer.auth.service.OwnedJob
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(ArchiveRollbackRequested event) {
        if (event.itemId() == null) executeBatch(event.batchId());
        else executeItem(event.itemId());
    }

    public void executeBatch(String batchId) {
        persistenceService.markBatchRunning(batchId);
        List<Long> ids = itemRepository.findByBatchIdOrderByIdAsc(batchId).stream()
                .map(ArchiveOperationItem::getId).filter(Objects::nonNull).toList();
        for (Long id : ids) executeItem(id);
        persistenceService.recomputeBatch(batchId);
    }

    public void executeItem(Long itemId) {
        ArchiveOperationItem item = persistenceService.claim(itemId);
        if (item == null) return;
        boolean readyForDatabase = false;
        try {
            if (resumeCompletedMetadata(item)) {
                persistenceService.markSucceeded(itemId);
                return;
            }
            validateCurrentState(item);
            ArchiveFormalSnapshot before = snapshotService.requireSource(item);
            ArchiveFormalSnapshot after = snapshotService.requireTarget(item);
            FileStoragePort.StoredObject restored;
            if (!Objects.equals(item.getSourcePath(), item.getTargetPath())) {
                if (storage.exists(item.getSourcePath())) {
                    // A durable marker is written only after this rollback copied
                    // and verified the source. COPYING alone cannot prove ownership.
                    if (!item.isRollbackCopyVerified())
                        throw new ArchiveRollbackConflictException("原路径已被占用，拒绝覆盖: " + item.getSourcePath());
                    FileStoragePort.StoredObject recordedCopy = storage.stat(item.getSourcePath());
                    if (!before.sha256().equalsIgnoreCase(recordedCopy.sha256())
                            || recordedCopy.size() != before.size())
                        throw new ArchiveRollbackConflictException("已记录的撤销副本内容不一致");
                } else {
                    persistenceService.markCopying(itemId);
                    storage.copy(item.getTargetPath(), item.getSourcePath(), after.sha256());
                }
            }
            restored = storage.stat(item.getSourcePath());
            if (!before.sha256().equalsIgnoreCase(restored.sha256())) {
                throw new ArchiveRollbackConflictException("恢复对象内容摘要不一致");
            }
            persistenceService.markDbCommitting(itemId);
            readyForDatabase = true;
            persistenceService.restoreMetadata(itemId, restored.etag(), restored.sha256());
            if (!Objects.equals(item.getSourcePath(), item.getTargetPath())) {
                try {
                    storage.delete(item.getTargetPath(), after.sha256());
                } catch (StorageObjectNotFoundException alreadyRemoved) {
                    // A crash may happen after cleanup and before marking the ledger complete.
                } catch (Exception cleanupError) {
                    compensationRegistry.register(item.getBatchId(), itemId,
                            com.coffer.governance.domain.GovernanceCompensationAction.DELETE_ROLLBACK_TARGET,
                            item.getTargetPath(), safeMessage(cleanupError));
                    return;
                }
            }
            persistenceService.markSucceeded(itemId);
        } catch (ArchiveRollbackConflictException e) {
            persistenceService.markConflicted(itemId, "ROLLBACK_CONFLICT", "文件状态或对象指纹已变化，无法安全撤销");
        } catch (ArchiveRollbackNotReversibleException e) {
            persistenceService.markNotReversible(itemId, "ROLLBACK_NOT_REVERSIBLE", "当前操作缺少可恢复的数据或对象");
        } catch (Exception e) {
            if (readyForDatabase) {
                compensationRegistry.register(item.getBatchId(), itemId,
                        com.coffer.governance.domain.GovernanceCompensationAction.RESUME_ROLLBACK,
                        item.getSourcePath(), safeMessage(e));
            }
            persistenceService.markFailed(itemId, "ROLLBACK_FAILED", safeMessage(e));
        }
    }

    /** Resume after the metadata transaction committed but object cleanup was interrupted. */
    private boolean resumeCompletedMetadata(ArchiveOperationItem item) {
        if (item.getRollbackResultRevision() == null) return false;
        ArchiveFormalSnapshot before = snapshotService.requireSource(item);
        ArchiveFormalSnapshot after = snapshotService.requireTarget(item);
        FileMetadata file = fileMetadataRepository.findById(item.getFileId()).orElse(null);
        if (file == null || !snapshotService.matchesAfterRollback(file, before, item.getRollbackResultRevision())) {
            return false;
        }
        FileStoragePort.StoredObject restored = storage.stat(before.path());
        if (!before.sha256().equalsIgnoreCase(restored.sha256())) {
            throw new ArchiveRollbackConflictException("恢复对象内容摘要不一致");
        }
        if (!Objects.equals(before.path(), after.path()) && storage.exists(after.path())) {
            storage.delete(after.path(), after.sha256());
        }
        return true;
    }

    private void validateCurrentState(ArchiveOperationItem item) {
        FileMetadata file = fileMetadataRepository.findById(item.getFileId())
                .orElseThrow(() -> new ArchiveRollbackNotReversibleException("正式文件已删除: " + item.getFileId()));
        ArchiveFormalSnapshot after = snapshotService.requireTarget(item);
        snapshotService.requireSource(item);
        if (!snapshotService.matches(file, after)
                || !Objects.equals(file.getContentSha256(), after.sha256())) {
            throw new ArchiveRollbackConflictException("文件正式状态已变化，拒绝撤销");
        }
        if (!storage.exists(item.getTargetPath())) {
            throw new ArchiveRollbackNotReversibleException("归档对象不存在: " + item.getTargetPath());
        }
        FileStoragePort.StoredObject target = storage.stat(item.getTargetPath());
        if (!after.sha256().equalsIgnoreCase(target.sha256())
                || target.size() != after.size()) {
            throw new ArchiveRollbackConflictException("归档对象指纹已变化，拒绝撤销");
        }
    }

    private String safeMessage(Exception e) {
        if (e instanceof ArchiveRollbackConflictException) {
            return "文件状态或对象指纹已变化，无法安全撤销";
        }
        if (e instanceof ArchiveRollbackNotReversibleException) {
            return "当前操作缺少可恢复的数据或对象";
        }
        if (e instanceof IllegalArgumentException) {
            return "撤销请求无效，请检查操作台账后重试";
        }
        return "撤销操作失败，请稍后重试";
    }
}
