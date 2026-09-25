package com.coffer.governance.application;

import com.coffer.file.domain.FileMetadata;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.governance.domain.ArchiveOperationItem;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationItemRepository;
import com.coffer.service.MinioStorageService;
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
@Service
@RequiredArgsConstructor
public class ArchiveRollbackService {
    private final ArchiveRollbackPersistenceService persistenceService;
    private final ArchiveOperationItemRepository itemRepository;
    private final FileMetadataRepository fileMetadataRepository;
    private final MinioStorageService minioStorageService;
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
            validateCurrentState(item);
            MinioStorageService.ObjectSnapshot restored;
            if (!Objects.equals(item.getSourcePath(), item.getTargetPath())) {
                if (minioStorageService.objectExists(null, item.getSourcePath())) {
                    MinioStorageService.ObjectSnapshot existing = minioStorageService.statFile(null, item.getSourcePath());
                    if (!Objects.equals(existing.etag(), item.getTargetEtag())
                            || !Objects.equals(existing.size(), item.getTargetSize())) {
                        throw new ArchiveRollbackConflictException("原路径已被占用，拒绝覆盖: " + item.getSourcePath());
                    }
                } else {
                    persistenceService.markCopying(itemId);
                    minioStorageService.copyObject(item.getTargetPath(), item.getSourcePath());
                }
            }
            restored = minioStorageService.statFile(null, item.getSourcePath());
            persistenceService.markDbCommitting(itemId);
            readyForDatabase = true;
            persistenceService.restoreMetadata(itemId, restored.etag());
            if (!Objects.equals(item.getSourcePath(), item.getTargetPath())) {
                try {
                    minioStorageService.deleteFile(null, item.getTargetPath());
                } catch (Exception cleanupError) {
                    compensationRegistry.register(item.getBatchId(), itemId,
                            com.coffer.governance.domain.GovernanceCompensationAction.DELETE_ROLLBACK_TARGET,
                            item.getTargetPath(), safeMessage(cleanupError));
                    return;
                }
            }
            persistenceService.markSucceeded(itemId);
        } catch (ArchiveRollbackConflictException e) {
            persistenceService.markConflicted(itemId, "ROLLBACK_CONFLICT", e.getMessage());
        } catch (ArchiveRollbackNotReversibleException e) {
            persistenceService.markNotReversible(itemId, "ROLLBACK_NOT_REVERSIBLE", e.getMessage());
        } catch (Exception e) {
            if (readyForDatabase) {
                compensationRegistry.register(item.getBatchId(), itemId,
                        com.coffer.governance.domain.GovernanceCompensationAction.RESUME_ROLLBACK,
                        item.getSourcePath(), safeMessage(e));
            }
            persistenceService.markFailed(itemId, "ROLLBACK_FAILED", safeMessage(e));
        }
    }

    private void validateCurrentState(ArchiveOperationItem item) {
        FileMetadata file = fileMetadataRepository.findById(item.getFileId())
                .orElseThrow(() -> new ArchiveRollbackNotReversibleException("正式文件已删除: " + item.getFileId()));
        if (!Objects.equals(file.getRevision(), item.getPostExecuteRevision())
                || !Objects.equals(file.getStoragePath(), item.getTargetPath())
                || !Objects.equals(file.getFileName(), item.getTargetFileName())
                || !Objects.equals(file.getCategory() == null ? null : file.getCategory().name(), item.getTargetCategory())) {
            throw new ArchiveRollbackConflictException("文件正式状态已变化，拒绝撤销");
        }
        if (!minioStorageService.objectExists(null, item.getTargetPath())) {
            throw new ArchiveRollbackNotReversibleException("归档对象不存在: " + item.getTargetPath());
        }
        MinioStorageService.ObjectSnapshot target = minioStorageService.statFile(null, item.getTargetPath());
        if (!Objects.equals(target.etag(), item.getTargetEtag())
                || !Objects.equals(target.size(), item.getTargetSize())) {
            throw new ArchiveRollbackConflictException("归档对象指纹已变化，拒绝撤销");
        }
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }
}
