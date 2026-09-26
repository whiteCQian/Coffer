package com.coffer.governance.application;

import com.coffer.config.GovernanceArchiveProperties;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.governance.api.dto.ArchiveOperationBatchResponse;
import com.coffer.governance.api.dto.ArchiveOperationItemResponse;
import com.coffer.governance.domain.ArchiveOperationBatch;
import com.coffer.governance.domain.ArchiveOperationBatchStatus;
import com.coffer.governance.domain.ArchiveOperationItem;
import com.coffer.governance.domain.ArchiveOperationItemExecutionStep;
import com.coffer.governance.domain.ArchiveOperationSource;
import com.coffer.governance.domain.GovernancePreviewBatch;
import com.coffer.governance.domain.GovernancePreviewItem;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationBatchRepository;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationItemRepository;
import com.coffer.governance.infrastructure.persistence.GovernancePreviewItemRepository;
import com.coffer.service.MinioStorageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Creates and executes durable archive operations produced by C06 confirmation. */
@Slf4j
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class ArchiveOperationService {

    private final ArchiveOperationBatchRepository batchRepository;
    private final ArchiveOperationItemRepository itemRepository;
    private final GovernancePreviewItemRepository previewItemRepository;
    private final FileMetadataRepository fileMetadataRepository;
    private final MinioStorageService minioStorageService;
    private final ArchiveOperationPersistenceService persistenceService;
    private final GovernanceArchiveProperties properties;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final GovernanceCompensationRegistry compensationRegistry;

    /** Persist the operation ledger in the confirmation transaction and schedule execution after commit. */
    @Transactional
    public ArchiveOperationBatchResponse createAndSchedule(GovernancePreviewBatch previewBatch,
                                                            List<GovernancePreviewItem> confirmedItems,
                                                            String requestId) {
        if (previewBatch == null || confirmedItems == null || confirmedItems.isEmpty()) {
            throw new IllegalArgumentException("没有可创建的归档操作明细");
        }
        String normalizedRequestId = requireRequestId(requestId);
        ArchiveOperationBatch existing = batchRepository.findByRequestId(normalizedRequestId).orElse(null);
        ArchiveOperationBatch batch;
        if (existing != null) {
            if (!Objects.equals(existing.getPreviewId(), previewBatch.getPreviewId())) {
                throw new IllegalArgumentException("确认请求幂等键已用于其他预览批次");
            }
            batch = existing;
        } else {
            batch = ArchiveOperationBatch.builder()
                    .batchId("archive-" + UUID.randomUUID())
                    .previewId(previewBatch.getPreviewId())
                    .source(ArchiveOperationSource.PREVIEW_CONFIRMATION)
                    .runMode(previewBatch.getRunMode())
                    .requestId(normalizedRequestId)
                    .status(ArchiveOperationBatchStatus.PENDING)
                    .totalCount(confirmedItems.size())
                    .build();
            batchRepository.saveAndFlush(batch);
            for (GovernancePreviewItem previewItem : confirmedItems) {
                ArchiveOperationItem item = ArchiveOperationItem.builder()
                        .batchId(batch.getBatchId())
                        .fileId(previewItem.getFileId())
                        .itemKey(batch.getBatchId() + ":" + previewItem.getFileId())
                        .expectedRevision(valueOrZero(previewItem.getSourceRevision()))
                        .sourceFileName(previewItem.getSourceFileName())
                        .targetFileName(previewItem.getSuggestedFileName())
                        .sourceCategory(previewItem.getSourceCategory())
                        .targetCategory(previewItem.getSuggestedCategory())
                        .sourcePath(previewItem.getSourcePath())
                        .targetPath(previewItem.getSuggestedPath())
                        .sourceEtag(previewItem.getSourceEtag())
                        .sourceSize(previewItem.getSourceSize())
                        .build();
                itemRepository.save(item);
            }
            itemRepository.flush();
        }
        // A repeated request is safe: item claims are protected by the durable status state machine.
        return toResponseAndSchedule(batch);
    }

    /** Query one operation batch and its file-level results. */
    @Transactional(readOnly = true)
    public ArchiveOperationBatchResponse get(String batchId) {
        return toResponse(requireBatch(batchId));
    }

    /** Reset FAILED items and schedule one more execution attempt. */
    @Transactional
    public ArchiveOperationBatchResponse retry(String batchId) {
        int reset = persistenceService.resetFailedItems(requireBatch(batchId).getBatchId());
        if (reset == 0) {
            throw new IllegalArgumentException("当前批次没有可重试的失败明细");
        }
        ArchiveOperationBatch batch = requireBatch(batchId);
        publishRequest(batch.getBatchId());
        return toResponse(batch);
    }

    /** Runs after the confirmation transaction has committed. */
    @Async("taskExecutor")
    @com.coffer.auth.service.OwnedJob
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(ArchiveOperationRequested event) {
        executeBatch(event.batchId());
    }

    /** Execute each file independently; one failure is persisted and does not stop the loop. */
    public void executeBatch(String batchId) {
        try {
            persistenceService.markBatchRunning(batchId);
            List<Long> itemIds = itemRepository.findByBatchIdOrderByIdAsc(batchId).stream()
                    .map(ArchiveOperationItem::getId)
                    .filter(Objects::nonNull)
                    .toList();
            for (Long itemId : itemIds) {
                try {
                    executeItem(itemId);
                } catch (Exception e) {
                    log.error("归档明细执行未处理异常，异常类型={}", e.getClass().getSimpleName());
                    safeMarkFailure(itemId, "EXECUTION_UNEXPECTED", safeMessage(e));
                }
            }
            persistenceService.recomputeBatch(batchId);
        } catch (Exception e) {
            log.error("归档批次执行失败，异常类型={}", e.getClass().getSimpleName());
        }
    }

    /** Latest operation is embedded in the preview response for the C06 page. */
    @Transactional(readOnly = true)
    public ArchiveOperationBatchResponse findLatestResponseByPreviewId(String previewId) {
        return batchRepository.findByPreviewIdOrderByCreatedAtDesc(previewId).stream()
                .findFirst()
                .map(this::toResponse)
                .orElse(null);
    }

    public void executeItem(Long itemId) {
        ArchiveOperationItem item = persistenceService.claimItem(itemId);
        if (item == null) {
            return;
        }
        if (item.getAttempts() > Math.max(1, properties.getMaxAttempts())) {
            persistenceService.markFailed(itemId, "MAX_ATTEMPTS", "已达到最大执行次数", null);
            return;
        }

        GovernancePreviewItem previewItem = previewItemRepository
                .findByPreviewIdAndFileId(requireBatch(item.getBatchId()).getPreviewId(), item.getFileId())
                .orElse(null);
        String summary = previewItem == null ? null : previewItem.getSuggestedSummary();
        List<String> tags = previewItem == null ? List.of() : readTags(previewItem.getSuggestedTags());
        boolean readyForDatabase = false;
        try {
            MinioStorageService.ObjectSnapshot targetSnapshot;
            if (isBeforeTargetCopied(item.getExecutionStep())) {
                validateSourceAndTarget(item);
                persistenceService.markSourceVerified(itemId);
                if (!Objects.equals(item.getSourcePath(), item.getTargetPath())
                        && !minioStorageService.objectExists(null, item.getTargetPath())) {
                    minioStorageService.copyObject(item.getSourcePath(), item.getTargetPath());
                }
                targetSnapshot = minioStorageService.statFile(null, item.getTargetPath());
                persistenceService.markTargetCopied(itemId, targetSnapshot.etag(), targetSnapshot.size());
            } else {
                targetSnapshot = minioStorageService.statFile(null, item.getTargetPath());
            }

            readyForDatabase = true;
            persistenceService.applyFormalState(itemId, targetSnapshot.etag(), targetSnapshot.size(), summary, tags);
            if (!Objects.equals(item.getSourcePath(), item.getTargetPath())) {
                try {
                    minioStorageService.deleteFile(null, item.getSourcePath());
                } catch (Exception cleanupError) {
                    persistenceService.markCleanupPending(itemId);
                    compensationRegistry.register(item.getBatchId(), itemId,
                            com.coffer.governance.domain.GovernanceCompensationAction.DELETE_ARCHIVE_SOURCE,
                            item.getSourcePath(), safeMessage(cleanupError));
                    return;
                }
            }
            persistenceService.markSucceeded(itemId);
        } catch (ArchiveExecutionConflictException e) {
            persistenceService.markConflicted(itemId, "EXECUTION_CONFLICT",
                    "文件状态或对象指纹已变化，请重新生成整理预览");
        } catch (Exception e) {
            if (readyForDatabase) {
                compensationRegistry.register(item.getBatchId(), itemId,
                        com.coffer.governance.domain.GovernanceCompensationAction.RESUME_ARCHIVE,
                        item.getTargetPath(), safeMessage(e));
            }
            safeMarkFailure(itemId, classifyFailure(e), safeMessage(e));
        }
    }

    private void validateSourceAndTarget(ArchiveOperationItem item) {
        FileMetadata metadata = fileMetadataRepository.findById(item.getFileId())
                .orElseThrow(() -> new ArchiveExecutionConflictException("正式文件不存在: " + item.getFileId()));
        long currentRevision = valueOrZero(metadata.getRevision());
        if (currentRevision != valueOrZero(item.getExpectedRevision())
                || !Objects.equals(metadata.getStoragePath(), item.getSourcePath())
                || !Objects.equals(metadata.getFileName(), item.getSourceFileName())
                || !sameCategory(metadata.getCategory(), item.getSourceCategory())) {
            throw new ArchiveExecutionConflictException("文件正式状态已变化，拒绝执行");
        }
        MinioStorageService.ObjectSnapshot source = minioStorageService.statFile(null, item.getSourcePath());
        if (!Objects.equals(item.getSourceEtag(), source.etag())
                || !Objects.equals(item.getSourceSize(), source.size())) {
            throw new ArchiveExecutionConflictException("源对象指纹已变化，拒绝执行");
        }
        if (!Objects.equals(item.getSourcePath(), item.getTargetPath())
                && minioStorageService.objectExists(null, item.getTargetPath())) {
            MinioStorageService.ObjectSnapshot existing = minioStorageService.statFile(null, item.getTargetPath());
            if (!Objects.equals(item.getSourceEtag(), existing.etag())
                    || !Objects.equals(item.getSourceSize(), existing.size())) {
                throw new ArchiveExecutionConflictException("归档目标路径已被占用: " + item.getTargetPath());
            }
        }
    }

    private ArchiveOperationBatchResponse toResponseAndSchedule(ArchiveOperationBatch batch) {
        publishRequest(batch.getBatchId());
        return toResponse(batch);
    }

    private void publishRequest(String batchId) {
        // Publishing through the application context is intentionally delayed until transaction commit.
        eventPublisher.publishEvent(new ArchiveOperationRequested(batchId));
    }

    private ArchiveOperationBatch requireBatch(String batchId) {
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("batchId 不能为空");
        }
        return batchRepository.findByBatchId(batchId)
                .orElseThrow(() -> new com.coffer.auth.service.ResourceNotFoundException());
    }

    private ArchiveOperationBatchResponse toResponse(ArchiveOperationBatch batch) {
        List<ArchiveOperationItemResponse> items = itemRepository.findByBatchIdOrderByIdAsc(batch.getBatchId())
                .stream().map(this::toItemResponse).toList();
        return new ArchiveOperationBatchResponse(
                batch.getBatchId(), batch.getPreviewId(), batch.getSource(), batch.getRunMode(),
                batch.getRequestId(), batch.getStatus(), batch.getRollbackStatus(), batch.getTotalCount(),
                batch.getSuccessCount(), batch.getFailedCount(), batch.getConflictedCount(),
                batch.getSkippedCount(), batch.getFailureSummary(), batch.getCreatedAt(), batch.getUpdatedAt(),
                batch.getStartedAt(), batch.getFinishedAt(), batch.getRollbackStartedAt(),
                batch.getRollbackFinishedAt(), items);
    }

    private ArchiveOperationItemResponse toItemResponse(ArchiveOperationItem item) {
        return new ArchiveOperationItemResponse(
                item.getId(), item.getBatchId(), item.getFileId(), item.getExpectedRevision(),
                item.getSourceFileName(), item.getTargetFileName(), item.getSourceCategory(),
                item.getTargetCategory(), item.getSourcePath(), item.getTargetPath(), item.getSourceEtag(),
                item.getSourceSize(), item.getTargetEtag(), item.getTargetSize(), item.getPreExecuteRevision(),
                item.getPostExecuteRevision(), item.getExecutionStatus(), item.getExecutionStep(),
                item.getRollbackStatus(),
                item.getAttempts(), item.getFailureCode(), item.getFailureMessage(), item.getCreatedAt(),
                item.getUpdatedAt(), item.getStartedAt(), item.getFinishedAt(), item.getRollbackStartedAt(),
                item.getRollbackFinishedAt());
    }

    private boolean isBeforeTargetCopied(ArchiveOperationItemExecutionStep step) {
        return step == null || step == ArchiveOperationItemExecutionStep.NONE
                || step == ArchiveOperationItemExecutionStep.SOURCE_VERIFIED;
    }

    private boolean sameCategory(com.coffer.file.domain.CategoryType category, String expected) {
        return Objects.equals(category == null ? null : category.name(), expected);
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private String requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("确认请求幂等键不能为空");
        }
        return requestId.trim();
    }

    private List<String> readTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            log.warn("归档读取预览标签失败，将跳过正式标签写入");
            return List.of();
        }
    }

    private void safeMarkFailure(Long itemId, String code, String message) {
        try {
            long delay = Math.max(1L, properties.getRetryDelaySeconds());
            persistenceService.markFailed(itemId, code, message,
                    LocalDateTime.now().plusSeconds(delay));
        } catch (Exception persistError) {
            log.error("归档失败状态写回失败，异常类型={}", persistError.getClass().getSimpleName());
        }
    }

    private String classifyFailure(Exception e) {
        String message = e.getMessage();
        if (message != null && message.contains("MinIO")) {
            return "MINIO_IO_FAILED";
        }
        return "EXECUTION_FAILED";
    }

    private String safeMessage(Exception e) {
        if (e instanceof ArchiveExecutionConflictException) {
            return "文件状态或对象指纹已变化，请重新生成整理预览";
        }
        if (e instanceof IllegalArgumentException) {
            return "归档请求无效，请检查整理预览后重试";
        }
        if (e instanceof RuntimeException runtime && runtime.getCause() != null) {
            return "对象存储操作失败，请稍后重试";
        }
        return "归档操作失败，请稍后重试";
    }

}
