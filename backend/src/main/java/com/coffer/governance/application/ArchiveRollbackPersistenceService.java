package com.coffer.governance.application;

import com.coffer.file.domain.CategoryType;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.governance.domain.*;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationBatchRepository;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** Short, locked database transactions for rollback orchestration. */
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class ArchiveRollbackPersistenceService {
    private final ArchiveOperationBatchRepository batchRepository;
    private final ArchiveOperationItemRepository itemRepository;
    private final FileMetadataRepository fileMetadataRepository;
    private final ArchiveSnapshotService snapshotService;

    @Transactional
    public boolean prepareBatch(String batchId) {
        ArchiveOperationBatch batch = lockBatch(batchId);
        List<ArchiveOperationItem> items = itemRepository.findByBatchIdOrderByIdAsc(batchId);
        int prepared = 0;
        for (ArchiveOperationItem item : items) {
            if (item.getExecutionStatus() == ArchiveOperationItemExecutionStatus.SUCCEEDED
                    && retryable(item.getRollbackStatus())) {
                prepare(item);
                prepared++;
            }
        }
        if (prepared == 0) {
            if (batch.getRollbackStatus() == ArchiveOperationRollbackStatus.SUCCEEDED
                    || batch.getRollbackStatus() == ArchiveOperationRollbackStatus.PENDING
                    || batch.getRollbackStatus() == ArchiveOperationRollbackStatus.RUNNING) {
                return false;
            }
            throw new IllegalArgumentException("当前批次没有可撤销的归档明细");
        }
        batch.setRollbackStatus(ArchiveOperationRollbackStatus.PENDING);
        batch.setRollbackFinishedAt(null);
        batchRepository.save(batch);
        return true;
    }

    @Transactional
    public boolean prepareItem(String batchId, Long itemId) {
        lockBatch(batchId);
        ArchiveOperationItem item = lockItem(itemId);
        if (!Objects.equals(item.getBatchId(), batchId)) {
            throw new IllegalArgumentException("归档明细不属于指定批次");
        }
        if (item.getExecutionStatus() != ArchiveOperationItemExecutionStatus.SUCCEEDED) {
            throw new IllegalArgumentException("只有归档成功的文件可以撤销");
        }
        if (item.getRollbackStatus() == ArchiveOperationItemRollbackStatus.SUCCEEDED
                || inFlight(item.getRollbackStatus())) {
            return false;
        }
        if (item.getRollbackStatus() == ArchiveOperationItemRollbackStatus.NOT_REVERSIBLE) {
            throw new IllegalArgumentException("该文件已不可撤销");
        }
        prepare(item);
        return true;
    }

    @Transactional
    public ArchiveOperationItem claim(Long itemId) {
        ArchiveOperationItem item = lockItem(itemId);
        if (item.getRollbackStatus() != ArchiveOperationItemRollbackStatus.PENDING) return null;
        item.setRollbackStatus(ArchiveOperationItemRollbackStatus.VALIDATING);
        item.setRollbackStartedAt(item.getRollbackStartedAt() == null ? LocalDateTime.now() : item.getRollbackStartedAt());
        item.setRollbackFinishedAt(null);
        itemRepository.save(item);
        return item;
    }

    @Transactional
    public void markCopying(Long itemId) {
        setStatus(itemId, ArchiveOperationItemRollbackStatus.COPYING, null, null, false);
    }

    @Transactional
    public void markDbCommitting(Long itemId) {
        ArchiveOperationItem item = lockItem(itemId);
        item.setRollbackCopyVerified(true);
        item.setRollbackStatus(ArchiveOperationItemRollbackStatus.DB_COMMITTING);
        itemRepository.save(item);
    }

    @Transactional
    public void restoreMetadata(Long itemId, String etag, String sha256) {
        ArchiveOperationItem item = lockItem(itemId);
        FileMetadata file = fileMetadataRepository.findByIdForUpdate(item.getFileId())
                .orElseThrow(() -> new ArchiveRollbackNotReversibleException("正式文件已删除: " + item.getFileId()));
        ArchiveFormalSnapshot before = snapshotService.requireSource(item);
        ArchiveFormalSnapshot after = snapshotService.requireTarget(item);
        if (!before.sha256().equalsIgnoreCase(sha256)
                || !after.sha256().equalsIgnoreCase(sha256)) {
            throw new ArchiveRollbackConflictException("撤销对象内容摘要与台账不一致");
        }
        long restoredRevision = after.revision() + 1;
        if (snapshotService.matchesAfterRollback(file, before, restoredRevision)) {
            if (!Objects.equals(file.getContentSha256(), sha256)) {
                throw new ArchiveRollbackConflictException("撤销后的正文指纹与台账不一致");
            }
        } else {
            if (!snapshotService.matches(file, after)
                    || !Objects.equals(file.getContentSha256(), after.sha256())) {
                throw new ArchiveRollbackConflictException("文件正式状态已变化，拒绝撤销");
            }
            file.setFileName(before.fileName());
            file.setCategory(CategoryType.fromLabel(before.category()));
            file.setStoragePath(before.path());
            file.setSummary(before.summary());
            file.setArchived(before.archived());
            file.setFileSize(before.size());
            file.setContentEtag(etag);
            file.setContentSha256(sha256);
            file.setRevision(restoredRevision);
            file.setVectorIndexedAt(null);
            fileMetadataRepository.saveAndFlush(file);
            snapshotService.replaceTags(file.getId(), before.tags());
            if (!snapshotService.matchesAfterRollback(file, before, restoredRevision)) {
                throw new IllegalStateException("撤销后的正式字段与原快照不一致");
            }
        }
        item.setRollbackResultRevision(restoredRevision);
        item.setRollbackStatus(ArchiveOperationItemRollbackStatus.CLEANUP_PENDING);
        itemRepository.save(item);
    }

    @Transactional
    public void markSucceeded(Long itemId) {
        ArchiveOperationItem item = lockItem(itemId);
        ArchiveFormalSnapshot before = snapshotService.requireSource(item);
        FileMetadata file = fileMetadataRepository.findById(item.getFileId())
                .orElseThrow(() -> new ArchiveRollbackNotReversibleException("正式文件已删除"));
        if (!snapshotService.matchesAfterRollback(file, before, item.getRollbackResultRevision())
                || !Objects.equals(file.getContentSha256(), before.sha256())) {
            throw new ArchiveRollbackConflictException("撤销后的正式状态与原快照不一致");
        }
        setStatus(itemId, ArchiveOperationItemRollbackStatus.SUCCEEDED, null, null, true);
    }

    @Transactional
    public void markFailed(Long itemId, String code, String message) {
        setStatus(itemId, ArchiveOperationItemRollbackStatus.FAILED, code, message, true);
    }

    @Transactional
    public void markConflicted(Long itemId, String code, String message) {
        setStatus(itemId, ArchiveOperationItemRollbackStatus.CONFLICTED, code, message, true);
    }

    @Transactional
    public void markNotReversible(Long itemId, String code, String message) {
        setStatus(itemId, ArchiveOperationItemRollbackStatus.NOT_REVERSIBLE, code, message, true);
    }

    @Transactional
    public void markBatchRunning(String batchId) {
        ArchiveOperationBatch batch = lockBatch(batchId);
        batch.setRollbackStatus(ArchiveOperationRollbackStatus.RUNNING);
        batch.setRollbackStartedAt(batch.getRollbackStartedAt() == null ? LocalDateTime.now() : batch.getRollbackStartedAt());
        batch.setRollbackFinishedAt(null);
        batchRepository.save(batch);
    }

    @Transactional
    public void recomputeBatch(String batchId) {
        ArchiveOperationBatch batch = lockBatch(batchId);
        // A file-level rollback does not turn the untouched remainder into a failed batch rollback.
        if (batch.getRollbackStatus() == ArchiveOperationRollbackStatus.NOT_REQUESTED) return;
        List<ArchiveOperationItem> items = itemRepository.findByBatchIdOrderByIdAsc(batchId).stream()
                .filter(i -> i.getExecutionStatus() == ArchiveOperationItemExecutionStatus.SUCCEEDED).toList();
        long succeeded = items.stream().filter(i -> i.getRollbackStatus() == ArchiveOperationItemRollbackStatus.SUCCEEDED).count();
        long failed = items.stream().filter(i -> i.getRollbackStatus() == ArchiveOperationItemRollbackStatus.FAILED
                || i.getRollbackStatus() == ArchiveOperationItemRollbackStatus.CONFLICTED
                || i.getRollbackStatus() == ArchiveOperationItemRollbackStatus.NOT_REVERSIBLE).count();
        boolean running = items.stream().anyMatch(i -> inFlight(i.getRollbackStatus()));
        if (running) batch.setRollbackStatus(ArchiveOperationRollbackStatus.RUNNING);
        else if (!items.isEmpty() && succeeded == items.size()) batch.setRollbackStatus(ArchiveOperationRollbackStatus.SUCCEEDED);
        else if (succeeded > 0 && failed > 0) batch.setRollbackStatus(ArchiveOperationRollbackStatus.PARTIAL_FAILED);
        else batch.setRollbackStatus(ArchiveOperationRollbackStatus.FAILED);
        batch.setRollbackFinishedAt(running ? null : LocalDateTime.now());
        batchRepository.save(batch);
    }

    @Transactional
    public void prepareRecovery(Long itemId) {
        ArchiveOperationItem item = lockItem(itemId);
        if (item.getRollbackStatus() == ArchiveOperationItemRollbackStatus.SUCCEEDED
                || item.getRollbackStatus() == ArchiveOperationItemRollbackStatus.CONFLICTED
                || item.getRollbackStatus() == ArchiveOperationItemRollbackStatus.NOT_REVERSIBLE) return;
        item.setRollbackStatus(ArchiveOperationItemRollbackStatus.PENDING);
        item.setRollbackFinishedAt(null);
        itemRepository.save(item);
    }

    private void prepare(ArchiveOperationItem item) {
        item.setRollbackStatus(ArchiveOperationItemRollbackStatus.PENDING);
        item.setRollbackCopyVerified(false);
        item.setRollbackStartedAt(null);
        item.setRollbackFinishedAt(null);
        item.setFailureCode(null);
        item.setFailureMessage(null);
        itemRepository.save(item);
    }

    private void setStatus(Long id, ArchiveOperationItemRollbackStatus status, String code, String message, boolean finished) {
        ArchiveOperationItem item = lockItem(id);
        item.setRollbackStatus(status);
        item.setFailureCode(code);
        item.setFailureMessage(message);
        item.setRollbackFinishedAt(finished ? LocalDateTime.now() : null);
        itemRepository.save(item);
    }

    private boolean retryable(ArchiveOperationItemRollbackStatus status) {
        return status == ArchiveOperationItemRollbackStatus.NOT_REQUESTED
                || status == ArchiveOperationItemRollbackStatus.FAILED
                || status == ArchiveOperationItemRollbackStatus.CONFLICTED;
    }

    private boolean inFlight(ArchiveOperationItemRollbackStatus status) {
        return status == ArchiveOperationItemRollbackStatus.PENDING
                || status == ArchiveOperationItemRollbackStatus.VALIDATING
                || status == ArchiveOperationItemRollbackStatus.COPYING
                || status == ArchiveOperationItemRollbackStatus.DB_COMMITTING
                || status == ArchiveOperationItemRollbackStatus.CLEANUP_PENDING;
    }

    private ArchiveOperationItem lockItem(Long id) {
        return itemRepository.findByIdForUpdate(id).orElseThrow(() -> new com.coffer.auth.service.ResourceNotFoundException());
    }
    private ArchiveOperationBatch lockBatch(String id) {
        return batchRepository.findByBatchIdForUpdate(id).orElseThrow(() -> new com.coffer.auth.service.ResourceNotFoundException());
    }
    private boolean sameCategory(CategoryType category, String expected) { return Objects.equals(category == null ? null : category.name(), expected); }
    private long value(Long value) { return value == null ? 0L : value; }
}
