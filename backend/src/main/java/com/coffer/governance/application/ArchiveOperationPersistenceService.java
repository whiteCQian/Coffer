package com.coffer.governance.application;

import com.coffer.file.domain.CategoryType;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.governance.domain.ArchiveOperationBatch;
import com.coffer.governance.domain.ArchiveOperationBatchStatus;
import com.coffer.governance.domain.ArchiveOperationItem;
import com.coffer.governance.domain.ArchiveOperationItemExecutionStatus;
import com.coffer.governance.domain.ArchiveOperationItemExecutionStep;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationBatchRepository;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationItemRepository;
import com.coffer.tag.domain.ConfirmationStatus;
import com.coffer.tag.domain.FileTagMapping;
import com.coffer.tag.domain.Tag;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.tag.infrastructure.persistence.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Short database transactions used by the external-IO archive executor. */
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class ArchiveOperationPersistenceService {

    private final ArchiveOperationBatchRepository batchRepository;
    private final ArchiveOperationItemRepository itemRepository;
    private final FileMetadataRepository fileMetadataRepository;
    private final TagRepository tagRepository;
    private final FileTagMappingRepository fileTagMappingRepository;

    @Transactional
    public ArchiveOperationItem claimItem(Long itemId) {
        ArchiveOperationItem item = itemRepository.findByIdForUpdate(itemId)
                .orElseThrow(() -> new com.coffer.auth.service.ResourceNotFoundException());
        if (item.getExecutionStatus() == ArchiveOperationItemExecutionStatus.SUCCEEDED
                || item.getExecutionStatus() == ArchiveOperationItemExecutionStatus.CONFLICTED
                || item.getExecutionStatus() == ArchiveOperationItemExecutionStatus.SKIPPED) {
            return null;
        }
        if (item.getExecutionStatus() == ArchiveOperationItemExecutionStatus.VALIDATING
                || item.getExecutionStatus() == ArchiveOperationItemExecutionStatus.COPYING
                || item.getExecutionStatus() == ArchiveOperationItemExecutionStatus.DB_COMMITTING
                || item.getExecutionStatus() == ArchiveOperationItemExecutionStatus.CLEANUP_PENDING) {
            // Another executor owns the durable in-flight step. C10 can add stale-run recovery.
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        item.setExecutionStatus(ArchiveOperationItemExecutionStatus.VALIDATING);
        item.setAttempts(item.getAttempts() + 1);
        item.setNextAttemptAt(null);
        item.setFailureCode(null);
        item.setFailureMessage(null);
        item.setStartedAt(item.getStartedAt() == null ? now : item.getStartedAt());
        item.setUpdatedAt(now);
        return itemRepository.save(item);
    }

    @Transactional
    public void markSourceVerified(Long itemId) {
        ArchiveOperationItem item = lockItem(itemId);
        item.setExecutionStatus(ArchiveOperationItemExecutionStatus.COPYING);
        item.setExecutionStep(ArchiveOperationItemExecutionStep.SOURCE_VERIFIED);
        item.setUpdatedAt(LocalDateTime.now());
        itemRepository.save(item);
    }

    @Transactional
    public void markTargetCopied(Long itemId, String etag, long size) {
        ArchiveOperationItem item = lockItem(itemId);
        item.setTargetEtag(etag);
        item.setTargetSize(size);
        item.setExecutionStatus(ArchiveOperationItemExecutionStatus.DB_COMMITTING);
        item.setExecutionStep(ArchiveOperationItemExecutionStep.TARGET_COPIED);
        item.setUpdatedAt(LocalDateTime.now());
        itemRepository.save(item);
    }

    @Transactional
    public void applyFormalState(Long itemId, String targetEtag, long targetSize,
                                 String summary, List<String> tags) {
        ArchiveOperationItem item = lockItem(itemId);
        FileMetadata metadata = fileMetadataRepository.findById(item.getFileId())
                .orElseThrow(() -> new ArchiveExecutionConflictException("正式文件不存在: " + item.getFileId()));

        long expectedRevision = valueOrZero(item.getExpectedRevision());
        long currentRevision = valueOrZero(metadata.getRevision());
        CategoryType targetCategory = CategoryType.fromLabel(item.getTargetCategory());
        boolean alreadyCommitted = currentRevision == expectedRevision + 1
                && Objects.equals(metadata.getStoragePath(), item.getTargetPath())
                && Objects.equals(metadata.getFileName(), item.getTargetFileName())
                && metadata.getCategory() == targetCategory;
        if (alreadyCommitted) {
            item.setPreExecuteRevision(expectedRevision);
            item.setPostExecuteRevision(currentRevision);
        } else {
            if (currentRevision != expectedRevision
                    || !Objects.equals(metadata.getStoragePath(), item.getSourcePath())
                    || !Objects.equals(metadata.getFileName(), item.getSourceFileName())
                    || !sameCategory(metadata.getCategory(), item.getSourceCategory())) {
                throw new ArchiveExecutionConflictException(
                        "文件正式状态已变化，拒绝覆盖 fileId=" + item.getFileId());
            }
            item.setPreExecuteRevision(currentRevision);
            metadata.setFileName(item.getTargetFileName());
            metadata.setCategory(targetCategory);
            metadata.setStoragePath(item.getTargetPath());
            metadata.setSummary(summary == null ? metadata.getSummary() : summary);
            metadata.setArchived(true);
            metadata.setContentEtag(targetEtag);
            metadata.setRevision(currentRevision + 1);
            metadata.setVectorIndexedAt(null);
            fileMetadataRepository.save(metadata);
            item.setPostExecuteRevision(metadata.getRevision());
        }

        // Also reconcile tags when the formal metadata was already committed.
        // This repairs executions produced by the previous incremental-tag logic
        // and keeps retries idempotent with the preview's confirmed tag set.
        applyConfirmedTags(metadata.getId(), tags);

        item.setTargetEtag(targetEtag == null ? item.getTargetEtag() : targetEtag);
        item.setTargetSize(targetSize);
        item.setExecutionStatus(ArchiveOperationItemExecutionStatus.CLEANUP_PENDING);
        item.setExecutionStep(ArchiveOperationItemExecutionStep.METADATA_COMMITTED);
        item.setUpdatedAt(LocalDateTime.now());
        itemRepository.save(item);
    }

    @Transactional
    public void markSucceeded(Long itemId) {
        ArchiveOperationItem item = lockItem(itemId);
        if (item.getExecutionStatus() == ArchiveOperationItemExecutionStatus.SUCCEEDED) {
            return;
        }
        item.setExecutionStatus(ArchiveOperationItemExecutionStatus.SUCCEEDED);
        item.setExecutionStep(ArchiveOperationItemExecutionStep.COMPLETED);
        item.setNextAttemptAt(null);
        item.setFinishedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        itemRepository.save(item);
    }

    @Transactional
    public void markCleanupPending(Long itemId) {
        ArchiveOperationItem item = lockItem(itemId);
        item.setExecutionStatus(ArchiveOperationItemExecutionStatus.CLEANUP_PENDING);
        item.setExecutionStep(ArchiveOperationItemExecutionStep.OLD_OBJECT_CLEANUP_PENDING);
        item.setUpdatedAt(LocalDateTime.now());
        itemRepository.save(item);
    }

    @Transactional
    public void markFailed(Long itemId, String code, String message, LocalDateTime nextAttemptAt) {
        ArchiveOperationItem item = lockItem(itemId);
        item.setExecutionStatus(ArchiveOperationItemExecutionStatus.FAILED);
        item.setFailureCode(code);
        item.setFailureMessage(message);
        item.setNextAttemptAt(nextAttemptAt);
        item.setFinishedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        itemRepository.save(item);
    }

    @Transactional
    public void markConflicted(Long itemId, String code, String message) {
        ArchiveOperationItem item = lockItem(itemId);
        item.setExecutionStatus(ArchiveOperationItemExecutionStatus.CONFLICTED);
        item.setFailureCode(code);
        item.setFailureMessage(message);
        item.setNextAttemptAt(null);
        item.setFinishedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        itemRepository.save(item);
    }

    @Transactional
    public void markBatchRunning(String batchId) {
        ArchiveOperationBatch batch = lockBatch(batchId);
        if (isTerminal(batch.getStatus())) {
            return;
        }
        batch.setStatus(ArchiveOperationBatchStatus.RUNNING);
        if (batch.getStartedAt() == null) {
            batch.setStartedAt(LocalDateTime.now());
        }
        batchRepository.save(batch);
    }

    @Transactional
    public ArchiveOperationBatch recomputeBatch(String batchId) {
        ArchiveOperationBatch batch = lockBatch(batchId);
        List<ArchiveOperationItem> items = itemRepository.findByBatchIdOrderByIdAsc(batchId);
        int success = count(items, ArchiveOperationItemExecutionStatus.SUCCEEDED);
        int failed = count(items, ArchiveOperationItemExecutionStatus.FAILED);
        int conflicted = count(items, ArchiveOperationItemExecutionStatus.CONFLICTED);
        int skipped = count(items, ArchiveOperationItemExecutionStatus.SKIPPED);
        boolean running = items.stream().anyMatch(item -> switch (item.getExecutionStatus()) {
            case PENDING, VALIDATING, COPYING, DB_COMMITTING, CLEANUP_PENDING -> true;
            default -> false;
        });

        batch.setTotalCount(items.size());
        batch.setSuccessCount(success);
        batch.setFailedCount(failed);
        batch.setConflictedCount(conflicted);
        batch.setSkippedCount(skipped);
        batch.setUpdatedAt(LocalDateTime.now());
        if (running) {
            batch.setStatus(ArchiveOperationBatchStatus.RUNNING);
            batch.setFinishedAt(null);
        } else if (items.isEmpty() || (success == 0 && (failed > 0 || conflicted > 0))) {
            batch.setStatus(ArchiveOperationBatchStatus.FAILED);
            batch.setFinishedAt(LocalDateTime.now());
        } else if (failed > 0 || conflicted > 0 || skipped > 0) {
            batch.setStatus(ArchiveOperationBatchStatus.PARTIAL_FAILED);
            batch.setFinishedAt(LocalDateTime.now());
        } else {
            batch.setStatus(ArchiveOperationBatchStatus.SUCCEEDED);
            batch.setFinishedAt(LocalDateTime.now());
        }
        batch.setFailureSummary(buildFailureSummary(items));
        return batchRepository.save(batch);
    }

    @Transactional
    public int resetFailedItems(String batchId) {
        ArchiveOperationBatch batch = lockBatch(batchId);
        List<ArchiveOperationItem> items = itemRepository.findByBatchIdOrderByIdAsc(batchId);
        int reset = 0;
        for (ArchiveOperationItem item : items) {
            if (item.getExecutionStatus() != ArchiveOperationItemExecutionStatus.FAILED) {
                continue;
            }
            item.setExecutionStatus(ArchiveOperationItemExecutionStatus.PENDING);
            item.setFailureCode(null);
            item.setFailureMessage(null);
            item.setNextAttemptAt(null);
            item.setFinishedAt(null);
            item.setUpdatedAt(LocalDateTime.now());
            itemRepository.save(item);
            reset++;
        }
        if (reset > 0) {
            batch.setStatus(ArchiveOperationBatchStatus.PENDING);
            batch.setFinishedAt(null);
            batch.setFailureSummary(null);
            batch.setUpdatedAt(LocalDateTime.now());
            batchRepository.save(batch);
        }
        return reset;
    }

    @Transactional
    public void prepareRecovery(Long itemId) {
        ArchiveOperationItem item = lockItem(itemId);
        if (item.getExecutionStatus() == ArchiveOperationItemExecutionStatus.SUCCEEDED
                || item.getExecutionStatus() == ArchiveOperationItemExecutionStatus.CONFLICTED) return;
        item.setExecutionStatus(ArchiveOperationItemExecutionStatus.PENDING);
        item.setNextAttemptAt(null);
        item.setFinishedAt(null);
        itemRepository.save(item);
    }

    private void applyConfirmedTags(Long fileId, List<String> tags) {
        if (tags == null) {
            return;
        }
        List<String> normalizedTags = tags.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(tag -> !tag.isBlank())
                .distinct()
                .toList();
        // The preview tag list is the user's confirmed target set, not an incremental
        // suggestion. Remove only this file's associations; keep the global Tag dictionary.
        // Always insert fresh mappings afterwards: looking an association up again would
        // silently degrade back to incremental-tag semantics if a delete ever failed.
        fileTagMappingRepository.deleteByFileId(fileId, com.coffer.auth.service.TenantContext.requireOwnerId());
        for (String tagName : normalizedTags) {
            Tag tag = tagRepository.findByTagName(tagName)
                    .orElseGet(() -> tagRepository.save(Tag.builder().tagName(tagName).build()));
            fileTagMappingRepository.save(FileTagMapping.builder()
                    .fileId(fileId)
                    .tagId(tag.getId())
                    .confirmationStatus(ConfirmationStatus.CONFIRMED)
                    .confirmedAt(LocalDateTime.now())
                    .build());
        }
        fileTagMappingRepository.flush();

        List<FileTagMapping> actualMappings = fileTagMappingRepository.findByFileId(fileId);
        Set<Long> actualTagIds = actualMappings.stream()
                .map(FileTagMapping::getTagId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> actualTags = tagRepository.findAllById(actualTagIds).stream()
                .map(Tag::getTagName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> expectedTags = new LinkedHashSet<>(normalizedTags);
        boolean allConfirmed = actualMappings.stream()
                .allMatch(mapping -> mapping.getConfirmationStatus() == ConfirmationStatus.CONFIRMED);
        if (!allConfirmed || actualMappings.size() != expectedTags.size() || !actualTags.equals(expectedTags)) {
            throw new IllegalStateException("正式标签集合校验失败 fileId=" + fileId
                    + ", expected=" + expectedTags + ", actual=" + actualTags);
        }
    }

    private ArchiveOperationItem lockItem(Long itemId) {
        return itemRepository.findByIdForUpdate(itemId)
                .orElseThrow(() -> new com.coffer.auth.service.ResourceNotFoundException());
    }

    private ArchiveOperationBatch lockBatch(String batchId) {
        return batchRepository.findByBatchIdForUpdate(batchId)
                .orElseThrow(() -> new com.coffer.auth.service.ResourceNotFoundException());
    }

    private int count(List<ArchiveOperationItem> items, ArchiveOperationItemExecutionStatus status) {
        return (int) items.stream().filter(item -> item.getExecutionStatus() == status).count();
    }

    private boolean sameCategory(CategoryType category, String expected) {
        return Objects.equals(category == null ? null : category.name(), expected);
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private boolean isTerminal(ArchiveOperationBatchStatus status) {
        return status == ArchiveOperationBatchStatus.SUCCEEDED
                || status == ArchiveOperationBatchStatus.CANCELLED;
    }

    private String buildFailureSummary(List<ArchiveOperationItem> items) {
        return items.stream()
                .filter(item -> item.getExecutionStatus() == ArchiveOperationItemExecutionStatus.FAILED
                        || item.getExecutionStatus() == ArchiveOperationItemExecutionStatus.CONFLICTED)
                .map(item -> "fileId=" + item.getFileId() + ": " + item.getFailureMessage())
                .findFirst()
                .orElse(null);
    }
}
