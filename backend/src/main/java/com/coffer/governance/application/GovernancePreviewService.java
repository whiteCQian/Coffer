package com.coffer.governance.application;

import com.coffer.config.GovernancePreviewProperties;
import com.coffer.dto.VisionResult;
import com.coffer.file.application.parse.DocumentParseService;
import com.coffer.file.domain.CategoryType;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.parse.ParseResult;
import com.coffer.file.domain.parse.ParseStatus;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.governance.api.dto.ConfirmGovernancePreviewRequest;
import com.coffer.governance.api.dto.ArchiveOperationBatchResponse;
import com.coffer.governance.api.dto.CreateGovernancePreviewRequest;
import com.coffer.governance.api.dto.GovernancePreviewItemResponse;
import com.coffer.governance.api.dto.GovernancePreviewResponse;
import com.coffer.governance.api.dto.RegenerateGovernancePreviewRequest;
import com.coffer.governance.api.dto.ReanalyzeGovernanceFilesRequest;
import com.coffer.governance.api.dto.SkipGovernancePreviewItemRequest;
import com.coffer.governance.api.dto.UpdateGovernancePreviewItemRequest;
import com.coffer.governance.domain.GovernancePreviewBatch;
import com.coffer.governance.domain.GovernancePreviewBatchStatus;
import com.coffer.governance.domain.GovernancePreviewItem;
import com.coffer.governance.domain.GovernancePreviewItemStatus;
import com.coffer.governance.domain.GovernancePreviewSource;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.governance.infrastructure.persistence.GovernancePreviewBatchRepository;
import com.coffer.governance.infrastructure.persistence.GovernancePreviewItemRepository;
import com.coffer.model.runtime.ModelRuntimeModeService;
import com.coffer.service.MinioStorageService;
import com.coffer.tag.api.dto.TagAndCategoryResult;
import com.coffer.service.VisionModelService;
import com.coffer.tool.TagGenerationTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Dry-run analysis service. It creates an immutable-enough preview snapshot while
 * deliberately avoiding writes to formal file metadata, tags, or MinIO objects.
 */
@Slf4j
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class GovernancePreviewService {

    private static final int SUMMARY_MAX_LENGTH = 1000;
    private static final List<String> IMAGE_EXTENSIONS = List.of(
            "jpg", "jpeg", "png", "gif", "webp", "bmp");
    private static final List<GovernancePreviewBatchStatus> EXPIRABLE_BATCH_STATUSES = List.of(
            GovernancePreviewBatchStatus.ANALYZING,
            GovernancePreviewBatchStatus.READY,
            GovernancePreviewBatchStatus.PARTIAL_READY,
            GovernancePreviewBatchStatus.FAILED);
    private static final List<GovernancePreviewItemStatus> EXPIRABLE_ITEM_STATUSES = List.of(
            GovernancePreviewItemStatus.PENDING,
            GovernancePreviewItemStatus.READY,
            GovernancePreviewItemStatus.EDITED,
            GovernancePreviewItemStatus.CONFLICTED,
            GovernancePreviewItemStatus.FAILED);

    private final GovernancePreviewBatchRepository batchRepository;
    private final GovernancePreviewItemRepository itemRepository;
    private final FileMetadataRepository fileMetadataRepository;
    private final MinioStorageService minioStorageService;
    private final DocumentParseService documentParseService;
    private final TagGenerationTool tagGenerationTool;
    private final VisionModelService visionModelService;
    private final ArchiveObjectNameService archiveObjectNameService;
    private final GovernancePreviewProperties properties;
    private final ObjectMapper objectMapper;

    /** Optional field injection preserves the focused constructor used by the C05/C06 unit tests. */
    @Autowired(required = false)
    private ArchiveOperationService archiveOperationService;

    /** Optional in focused unit tests; production resolves the validated global mode. */
    @Autowired(required = false)
    private ModelRuntimeModeService runtimeModeService;

    /** Create a preview, or return the original result for an identical request retry. */
    @Transactional
    public GovernancePreviewResponse create(CreateGovernancePreviewRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("预览请求不能为空");
        }
        List<Long> fileIds = normalizeFileIds(request.getFileIds());
        String requestId = requireText(request.getRequestId(), "预览请求幂等键不能为空");
        GovernancePreviewSource source = request.getSource() == null
                ? GovernancePreviewSource.UPLOAD : request.getSource();
        GovernanceRunMode mode = resolveMode(request.getMode());

        GovernancePreviewBatch existing = batchRepository.findByRequestId(requestId).orElse(null);
        if (existing != null) {
            assertSameRequest(existing, fileIds, source, mode);
            expireIfNecessary(existing, LocalDateTime.now());
            return toResponse(existing);
        }

        return withModeSnapshot(mode, () -> createInternal(fileIds, requestId, source, mode));
    }

    /** Create a fresh preview for existing files without changing their formal metadata. */
    @Transactional
    public GovernancePreviewResponse reanalyze(ReanalyzeGovernanceFilesRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("重新分析请求不能为空");
        }
        String requestId = requireText(request.getRequestId(), "重新分析请求幂等键不能为空");
        List<Long> fileIds = normalizeFileIds(request.getFileIds());
        GovernanceRunMode mode = request.getMode();
        return create(new CreateGovernancePreviewRequest(fileIds, requestId,
                GovernancePreviewSource.REANALYZE, mode));
    }

    /** Return a preview and lazily mark it expired when its retention window has elapsed. */
    @Transactional
    public GovernancePreviewResponse get(String previewId) {
        GovernancePreviewBatch batch = requireBatch(previewId);
        expireIfNecessary(batch, LocalDateTime.now());
        return toResponse(batch);
    }

    /** Generate a fresh preview from a previous preview's file set. */
    @Transactional
    public GovernancePreviewResponse regenerate(String previewId, RegenerateGovernancePreviewRequest request) {
        GovernancePreviewBatch previous = requireBatch(previewId);
        expireIfNecessary(previous, LocalDateTime.now());
        if (request == null || request.getRequestId() == null || request.getRequestId().isBlank()) {
            throw new IllegalArgumentException("重新生成预览必须提供新的幂等键");
        }
        if (previous.getStatus() == GovernancePreviewBatchStatus.CONFIRMED
                || previous.getStatus() == GovernancePreviewBatchStatus.CONFIRMING
                || previous.getStatus() == GovernancePreviewBatchStatus.PARTIALLY_CONFIRMED) {
            throw new IllegalArgumentException("已进入确认流程的预览不可重新生成");
        }
        List<Long> fileIds = itemRepository.findByPreviewIdOrderByIdAsc(previewId).stream()
                .map(GovernancePreviewItem::getFileId)
                .distinct()
                .toList();
        GovernanceRunMode mode = runtimeModeService == null
                ? previous.getRunMode() : resolveMode(null);
        return withModeSnapshot(mode, () -> createInternal(fileIds, request.getRequestId(),
                GovernancePreviewSource.RETRY, mode));
    }

    private GovernanceRunMode resolveMode(GovernanceRunMode requestedMode) {
        if (runtimeModeService == null) {
            return requestedMode == null ? GovernanceRunMode.API : requestedMode;
        }
        return runtimeModeService.resolveForTask(requestedMode);
    }

    private <T> T withModeSnapshot(GovernanceRunMode mode, java.util.function.Supplier<T> action) {
        return runtimeModeService == null ? action.get() : runtimeModeService.withSnapshot(mode, action);
    }

    /** Update one suggestion without touching formal file metadata or objects. */
    @Transactional
    public GovernancePreviewResponse updateItem(String previewId, Long itemId,
                                                 UpdateGovernancePreviewItemRequest request) {
        GovernancePreviewBatch batch = requireBatch(previewId);
        expireIfNecessary(batch, LocalDateTime.now());
        assertEditableBatch(batch);
        GovernancePreviewItem item = requireItem(previewId, itemId);
        if (!isEditableItem(item.getStatus())) {
            throw new IllegalArgumentException("当前预览项不可编辑: " + item.getStatus());
        }
        if (request == null) {
            throw new IllegalArgumentException("预览项修改内容不能为空");
        }

        String fileName = requireText(request.getSuggestedFileName(), "建议文件名不能为空");
        List<String> tags = normalizeTags(request.getSuggestedTags());
        if (tags.isEmpty()) {
            throw new IllegalArgumentException("至少保留一个建议标签");
        }
        FileMetadata metadata = fileMetadataRepository.findById(item.getFileId())
                .orElseThrow(() -> new com.coffer.auth.service.ResourceNotFoundException());
        CategoryType category = request.getSuggestedCategory() == null
                ? CategoryType.OTHER : request.getSuggestedCategory();
        String suggestedPath = archiveObjectNameService.generateArchivePath(
                category, fileName, metadata.getUploadTime());

        item.setSuggestedFileName(fileName);
        item.setSuggestedCategory(category.name());
        item.setSuggestedPath(suggestedPath);
        item.setSuggestedSummary(limitSummary(request.getSuggestedSummary()));
        item.setSuggestedTags(writeTags(tags));
        item.setSkipReason(null);
        item.setUpdatedAt(LocalDateTime.now());
        if (minioStorageService.objectExists(null, suggestedPath)) {
            item.setStatus(GovernancePreviewItemStatus.CONFLICTED);
            item.setErrorCode("TARGET_PATH_CONFLICT");
            item.setErrorMessage("预计归档路径已存在，确认前必须修改建议");
        } else {
            item.setStatus(GovernancePreviewItemStatus.EDITED);
            item.setErrorCode(null);
            item.setErrorMessage(null);
        }
        itemRepository.save(item);
        refreshBatchState(batch);
        return toResponse(batch);
    }

    /** Mark one item as intentionally skipped. */
    @Transactional
    public GovernancePreviewResponse skipItem(String previewId, Long itemId,
                                               SkipGovernancePreviewItemRequest request) {
        GovernancePreviewBatch batch = requireBatch(previewId);
        expireIfNecessary(batch, LocalDateTime.now());
        assertEditableBatch(batch);
        GovernancePreviewItem item = requireItem(previewId, itemId);
        if (item.getStatus() == GovernancePreviewItemStatus.CONFIRMED) {
            throw new IllegalArgumentException("已确认的预览项不可跳过");
        }
        if (item.getStatus() == GovernancePreviewItemStatus.SKIPPED) {
            return toResponse(batch);
        }
        if (item.getStatus() == GovernancePreviewItemStatus.EXPIRED
                || item.getStatus() == GovernancePreviewItemStatus.CANCELLED) {
            throw new IllegalArgumentException("当前预览项不可跳过: " + item.getStatus());
        }
        String reason = request == null || request.getReason() == null
                || request.getReason().isBlank() ? "用户主动跳过" : request.getReason().trim();
        item.setStatus(GovernancePreviewItemStatus.SKIPPED);
        item.setSkipReason(reason);
        item.setErrorCode(null);
        item.setErrorMessage(null);
        item.setUpdatedAt(LocalDateTime.now());
        itemRepository.save(item);
        refreshBatchState(batch);
        return toResponse(batch);
    }

    /** Confirm all or a selected subset of actionable suggestions for later C07 execution. */
    @Transactional
    public GovernancePreviewResponse confirm(String previewId, ConfirmGovernancePreviewRequest request) {
        GovernancePreviewBatch batch = requireBatchForUpdate(previewId);
        expireIfNecessary(batch, LocalDateTime.now());
        if (batch.getStatus() == GovernancePreviewBatchStatus.EXPIRED
                || batch.getStatus() == GovernancePreviewBatchStatus.CANCELLED) {
            throw new IllegalArgumentException("预览批次已终止，不可确认: " + batch.getStatus());
        }
        List<GovernancePreviewItem> items = itemRepository.findByPreviewIdOrderByIdAsc(previewId);
        List<Long> selectedIds = selectConfirmationIds(items, request);
        if (selectedIds.isEmpty()) {
            throw new IllegalArgumentException("没有可确认的预览项");
        }

        List<GovernancePreviewItem> newlyConfirmed = new ArrayList<>();
        for (GovernancePreviewItem item : items) {
            if (!selectedIds.contains(item.getId())) {
                continue;
            }
            if (item.getStatus() == GovernancePreviewItemStatus.CONFIRMED) {
                continue;
            }
            if (!isConfirmableItem(item.getStatus())) {
                throw new IllegalArgumentException("预览项不可确认: " + item.getId()
                        + " (" + item.getStatus() + ")");
            }
            if (!revalidateForConfirmation(item)) {
                itemRepository.save(item);
                continue;
            }
            item.setStatus(GovernancePreviewItemStatus.CONFIRMED);
            item.setConfirmedAt(LocalDateTime.now());
            item.setUpdatedAt(LocalDateTime.now());
            item.setErrorCode(null);
            item.setErrorMessage(null);
            itemRepository.save(item);
            newlyConfirmed.add(item);
        }
        if (batch.getConfirmedAt() == null) {
            batch.setConfirmedAt(LocalDateTime.now());
        }
        refreshBatchState(batch);
        if (!newlyConfirmed.isEmpty() && archiveOperationService != null) {
            archiveOperationService.createAndSchedule(batch, newlyConfirmed,
                    confirmationRequestId(previewId, request, selectedIds));
        }
        return toResponse(batch);
    }

    /** Cancel a preview before any item has been confirmed. */
    @Transactional
    public GovernancePreviewResponse cancel(String previewId) {
        GovernancePreviewBatch batch = requireBatch(previewId);
        expireIfNecessary(batch, LocalDateTime.now());
        if (batch.getStatus() == GovernancePreviewBatchStatus.CANCELLED) {
            return toResponse(batch);
        }
        if (batch.getStatus() == GovernancePreviewBatchStatus.EXPIRED) {
            throw new IllegalArgumentException("预览批次已过期，不可取消");
        }
        if (batch.getStatus() == GovernancePreviewBatchStatus.CONFIRMED
                || batch.getStatus() == GovernancePreviewBatchStatus.PARTIALLY_CONFIRMED
                || batch.getStatus() == GovernancePreviewBatchStatus.CONFIRMING) {
            throw new IllegalArgumentException("已进入确认流程的预览不可取消");
        }
        List<GovernancePreviewItem> items = itemRepository.findByPreviewIdOrderByIdAsc(previewId);
        LocalDateTime now = LocalDateTime.now();
        for (GovernancePreviewItem item : items) {
            if (item.getStatus() != GovernancePreviewItemStatus.CONFIRMED) {
                item.setStatus(GovernancePreviewItemStatus.CANCELLED);
                item.setUpdatedAt(now);
            }
        }
        itemRepository.saveAll(items);
        batch.setStatus(GovernancePreviewBatchStatus.CANCELLED);
        batch.setCancelledAt(now);
        batch.setUpdatedAt(now);
        batchRepository.save(batch);
        return toResponse(batch);
    }

    private void assertEditableBatch(GovernancePreviewBatch batch) {
        if (batch.getStatus() == GovernancePreviewBatchStatus.EXPIRED
                || batch.getStatus() == GovernancePreviewBatchStatus.CANCELLED
                || batch.getStatus() == GovernancePreviewBatchStatus.CONFIRMED) {
            throw new IllegalArgumentException("当前预览批次不可修改: " + batch.getStatus());
        }
    }

    private GovernancePreviewItem requireItem(String previewId, Long itemId) {
        if (itemId == null) {
            throw new IllegalArgumentException("预览项 ID 不能为空");
        }
        GovernancePreviewItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new com.coffer.auth.service.ResourceNotFoundException());
        if (!Objects.equals(previewId, item.getPreviewId())) {
            throw new IllegalArgumentException("预览项不属于当前预览批次");
        }
        return item;
    }

    private boolean isEditableItem(GovernancePreviewItemStatus status) {
        return status == GovernancePreviewItemStatus.READY
                || status == GovernancePreviewItemStatus.EDITED
                || status == GovernancePreviewItemStatus.CONFLICTED;
    }

    private boolean isConfirmableItem(GovernancePreviewItemStatus status) {
        return status == GovernancePreviewItemStatus.READY
                || status == GovernancePreviewItemStatus.EDITED;
    }

    private List<Long> selectConfirmationIds(List<GovernancePreviewItem> items,
                                              ConfirmGovernancePreviewRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("确认请求不能为空");
        }
        List<Long> selected = request.isConfirmAll()
                ? items.stream()
                .filter(item -> isConfirmableItem(item.getStatus())
                        || item.getStatus() == GovernancePreviewItemStatus.CONFIRMED)
                .map(GovernancePreviewItem::getId)
                .toList()
                : normalizeItemIds(request.getItemIds());
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("没有可确认的预览项");
        }
        if (!request.isConfirmAll()) {
            for (Long itemId : selected) {
                if (items.stream().noneMatch(item -> Objects.equals(item.getId(), itemId))) {
                    throw new IllegalArgumentException("预览项不属于当前预览批次: " + itemId);
                }
            }
        }
        return selected;
    }

    private List<Long> normalizeItemIds(Collection<Long> itemIds) {
        if (itemIds == null) {
            return List.of();
        }
        return itemIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    /** Recheck the source snapshot and target path immediately before confirmation. */
    private boolean revalidateForConfirmation(GovernancePreviewItem item) {
        FileMetadata metadata = fileMetadataRepository.findById(item.getFileId()).orElse(null);
        if (metadata == null) {
            item.setStatus(GovernancePreviewItemStatus.FAILED);
            item.setErrorCode("FILE_NOT_FOUND");
            item.setErrorMessage("正式文件不存在，无法确认");
            item.setUpdatedAt(LocalDateTime.now());
            return false;
        }
        MinioStorageService.ObjectSnapshot current;
        try {
            current = statSource(metadata);
        } catch (PreviewAnalysisException e) {
            item.setStatus(GovernancePreviewItemStatus.FAILED);
            item.setErrorCode(e.code);
            item.setErrorMessage(e.getMessage());
            item.setUpdatedAt(LocalDateTime.now());
            return false;
        }
        if (!Objects.equals(item.getSourceRevision(), defaultRevision(metadata))
                || !Objects.equals(item.getSourceEtag(), current.etag())
                || !Objects.equals(item.getSourceSize(), current.size())) {
            item.setStatus(GovernancePreviewItemStatus.CONFLICTED);
            item.setErrorCode("SOURCE_CHANGED");
            item.setErrorMessage("文件在预览后发生变化，请重新生成预览");
            item.setUpdatedAt(LocalDateTime.now());
            return false;
        }
        if (item.getSuggestedPath() == null || item.getSuggestedPath().isBlank()) {
            item.setStatus(GovernancePreviewItemStatus.CONFLICTED);
            item.setErrorCode("TARGET_PATH_MISSING");
            item.setErrorMessage("缺少预计归档路径，请先修改建议");
            item.setUpdatedAt(LocalDateTime.now());
            return false;
        }
        if (minioStorageService.objectExists(null, item.getSuggestedPath())) {
            item.setStatus(GovernancePreviewItemStatus.CONFLICTED);
            item.setErrorCode("TARGET_PATH_CONFLICT");
            item.setErrorMessage("预计归档路径已存在，确认前必须修改建议");
            item.setUpdatedAt(LocalDateTime.now());
            return false;
        }
        return true;
    }

    private void refreshBatchState(GovernancePreviewBatch batch) {
        List<GovernancePreviewItem> items = itemRepository
                .findByPreviewIdOrderByIdAsc(batch.getPreviewId());
        int readyCount = (int) items.stream()
                .filter(item -> item.getStatus() == GovernancePreviewItemStatus.READY
                        || item.getStatus() == GovernancePreviewItemStatus.EDITED
                        || item.getStatus() == GovernancePreviewItemStatus.CONFLICTED)
                .count();
        int failedCount = (int) items.stream()
                .filter(item -> item.getStatus() == GovernancePreviewItemStatus.FAILED)
                .count();
        boolean confirmed = items.stream()
                .anyMatch(item -> item.getStatus() == GovernancePreviewItemStatus.CONFIRMED);
        boolean actionable = items.stream()
                .anyMatch(item -> item.getStatus() == GovernancePreviewItemStatus.PENDING
                        || item.getStatus() == GovernancePreviewItemStatus.READY
                        || item.getStatus() == GovernancePreviewItemStatus.EDITED
                        || item.getStatus() == GovernancePreviewItemStatus.CONFLICTED);
        boolean skipped = items.stream()
                .anyMatch(item -> item.getStatus() == GovernancePreviewItemStatus.SKIPPED);

        batch.setReadyCount(readyCount);
        batch.setFailedCount(failedCount);
        if (confirmed) {
            batch.setStatus(actionable
                    ? GovernancePreviewBatchStatus.PARTIALLY_CONFIRMED
                    : GovernancePreviewBatchStatus.CONFIRMED);
        } else if (actionable) {
            batch.setStatus(failedCount > 0 || skipped || readyCount == 0
                    ? GovernancePreviewBatchStatus.PARTIAL_READY
                    : GovernancePreviewBatchStatus.READY);
        } else if (failedCount > 0) {
            batch.setStatus(GovernancePreviewBatchStatus.FAILED);
        } else if (skipped) {
            // A skipped-only preview remains cancellable and visible until C07 decides its execution.
            batch.setStatus(GovernancePreviewBatchStatus.PARTIAL_READY);
        }
        batch.setUpdatedAt(LocalDateTime.now());
        batchRepository.save(batch);
    }

    /** Expire preview batches and their still-actionable items without touching formal file data. */
    @Transactional
    @com.coffer.auth.service.OwnerScheduled
    @Scheduled(fixedDelayString = "${coffer.governance.preview.expiry-scan-delay-ms:60000}")
    public void expireDuePreviews() {
        LocalDateTime now = LocalDateTime.now();
        List<GovernancePreviewBatch> due = batchRepository
                .findByStatusInAndExpiresAtBefore(EXPIRABLE_BATCH_STATUSES, now);
        for (GovernancePreviewBatch batch : due) {
            expireIfNecessary(batch, now);
        }
    }

    private GovernancePreviewResponse createInternal(List<Long> fileIds, String requestId,
                                                      GovernancePreviewSource source,
                                                      GovernanceRunMode mode) {
        LocalDateTime now = LocalDateTime.now();
        GovernancePreviewBatch batch = GovernancePreviewBatch.builder()
                .previewId(UUID.randomUUID().toString())
                .source(source)
                .runMode(mode)
                .modelSnapshotId(com.coffer.model.runtime.ModelExecutionContext.currentId())
                .requestId(requestId)
                .createdBy("LOCAL_USER")
                .status(GovernancePreviewBatchStatus.ANALYZING)
                .totalCount(fileIds.size())
                .createdAt(now)
                .updatedAt(now)
                .expiresAt(now.plusMinutes(Math.max(1L, properties.getExpirationMinutes())))
                .build();
        batchRepository.saveAndFlush(batch);

        int readyCount = 0;
        int failedCount = 0;
        int conflictedCount = 0;
        for (Long fileId : fileIds) {
            GovernancePreviewItem item;
            FileMetadata metadata = fileMetadataRepository.findById(fileId).orElse(null);
            if (metadata == null) {
                item = failedItem(batch.getPreviewId(), fileId, null,
                        "FILE_NOT_FOUND", "文件不存在", mode);
            } else {
                item = analyzeOne(batch, metadata, mode);
            }
            itemRepository.save(item);
            if (item.getStatus() == GovernancePreviewItemStatus.READY
                    || item.getStatus() == GovernancePreviewItemStatus.CONFLICTED) {
                readyCount++;
            }
            if (item.getStatus() == GovernancePreviewItemStatus.CONFLICTED) {
                conflictedCount++;
            }
            if (item.getStatus() == GovernancePreviewItemStatus.FAILED) {
                failedCount++;
            }
        }

        batch.setReadyCount(readyCount);
        batch.setFailedCount(failedCount);
        batch.setStatus(resolveBatchStatus(fileIds.size(), readyCount, failedCount, conflictedCount));
        batch.setUpdatedAt(LocalDateTime.now());
        batchRepository.save(batch);
        return toResponse(batch);
    }

    private GovernancePreviewItem analyzeOne(GovernancePreviewBatch batch, FileMetadata metadata,
                                             GovernanceRunMode mode) {
        GovernancePreviewItem item = baseItem(batch.getPreviewId(), metadata, mode);
        try {
            MinioStorageService.ObjectSnapshot before = statSource(metadata);
            applySourceSnapshot(item, metadata, before);

            AnalysisResult result = isImage(metadata.getFileType())
                    ? analyzeImage(metadata)
                    : analyzeText(metadata);
            String suggestedFileName = metadata.getFileName();
            CategoryType suggestedCategory = result.category() == null
                    ? CategoryType.OTHER : result.category();
            item.setSuggestedFileName(suggestedFileName);
            item.setSuggestedCategory(suggestedCategory.name());
            item.setSuggestedPath(archiveObjectNameService.generateArchivePath(
                    suggestedCategory, suggestedFileName, metadata.getUploadTime()));
            item.setSuggestedSummary(limitSummary(result.summary()));
            item.setSuggestedTags(writeTags(result.tags()));

            MinioStorageService.ObjectSnapshot after = statSource(metadata);
            if (!sameSnapshot(before, after)
                    || !Objects.equals(item.getSourceRevision(), defaultRevision(metadata))) {
                item.setStatus(GovernancePreviewItemStatus.CONFLICTED);
                item.setErrorCode("SOURCE_CHANGED");
                item.setErrorMessage("分析期间文件对象或治理版本发生变化，请重新生成预览");
            } else if (minioStorageService.objectExists(null, item.getSuggestedPath())) {
                item.setStatus(GovernancePreviewItemStatus.CONFLICTED);
                item.setErrorCode("TARGET_PATH_CONFLICT");
                item.setErrorMessage("预计归档路径已存在，确认前必须修改建议");
            } else {
                item.setStatus(GovernancePreviewItemStatus.READY);
            }
        } catch (PreviewAnalysisException e) {
            item.setStatus(GovernancePreviewItemStatus.FAILED);
            item.setErrorCode(e.code);
            item.setErrorMessage(e.getMessage());
        } catch (Exception e) {
            log.warn("Dry-run 预览项分析失败，异常类型={}", e.getClass().getSimpleName());
            item.setStatus(GovernancePreviewItemStatus.FAILED);
            item.setErrorCode("ANALYSIS_FAILED");
            item.setErrorMessage(safeMessage(e));
        }
        return item;
    }

    private AnalysisResult analyzeText(FileMetadata metadata) {
        try (InputStream stream = minioStorageService.getFileStream(null, metadata.getStoragePath())) {
            ParseResult parseResult = documentParseService.extractTextWithFallback(metadata.getFileName(), stream);
            if (parseResult.getStatus() != ParseStatus.SUCCESS) {
                throw new PreviewAnalysisException("PARSE_" + parseResult.getStatus().name(),
                        "文件解析失败，请检查文件格式");
            }
            String content = parseResult.getContent();
            TagAndCategoryResult tagResult = tagGenerationTool.generateTagAndCategory(content);
            if (tagResult == null) {
                throw new PreviewAnalysisException("AI_RESULT_EMPTY", "AI 未返回分类和标签结果");
            }
            List<String> tags = normalizeTags(tagResult == null ? null : tagResult.tags());
            if (tags.isEmpty()) {
                throw new PreviewAnalysisException("NO_TAGS", "AI 未生成有效标签");
            }
            return new AnalysisResult(tagResult.category(), tags, content);
        } catch (PreviewAnalysisException e) {
            throw e;
        } catch (Exception e) {
            throw new PreviewAnalysisException("ANALYSIS_FAILED", safeMessage(e), e);
        }
    }

    private AnalysisResult analyzeImage(FileMetadata metadata) {
        try (InputStream stream = minioStorageService.getFileStream(null, metadata.getStoragePath())) {
            byte[] bytes = stream.readAllBytes();
            VisionResult vision = visionModelService.describeImage(
                    java.util.Base64.getEncoder().encodeToString(bytes), imageMime(metadata.getFileType()),
                    metadata.getFileName());
            if (vision == null) {
                throw new PreviewAnalysisException("AI_RESULT_EMPTY", "AI 未返回图片分析结果");
            }
            List<String> tags = normalizeTags(vision == null ? null : vision.tags());
            if (tags.isEmpty()) {
                throw new PreviewAnalysisException("NO_TAGS", "AI 未生成有效标签");
            }
            return new AnalysisResult(vision.category(), tags, vision.description());
        } catch (PreviewAnalysisException e) {
            throw e;
        } catch (Exception e) {
            throw new PreviewAnalysisException("ANALYSIS_FAILED", safeMessage(e), e);
        }
    }

    private GovernancePreviewItem baseItem(String previewId, FileMetadata metadata, GovernanceRunMode mode) {
        return GovernancePreviewItem.builder()
                .previewId(previewId)
                .fileId(metadata.getId())
                .sourceRevision(defaultRevision(metadata))
                .sourcePath(metadata.getStoragePath())
                .sourceFileName(metadata.getFileName())
                .sourceCategory(categoryName(metadata.getCategory()))
                .sourceSize(metadata.getFileSize())
                .analysisModel(resolveAnalysisModel(metadata))
                .analysisMode(mode)
                .status(GovernancePreviewItemStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private GovernancePreviewItem failedItem(String previewId, Long fileId, FileMetadata metadata,
                                             String errorCode, String errorMessage, GovernanceRunMode mode) {
        GovernancePreviewItem.GovernancePreviewItemBuilder builder = GovernancePreviewItem.builder()
                .previewId(previewId)
                .fileId(fileId)
                .sourceRevision(metadata == null ? 0L : defaultRevision(metadata))
                .sourcePath(metadata == null ? null : metadata.getStoragePath())
                .sourceFileName(metadata == null ? null : metadata.getFileName())
                .sourceCategory(metadata == null ? null : categoryName(metadata.getCategory()))
                .sourceSize(metadata == null ? null : metadata.getFileSize())
                .analysisModel(metadata == null ? properties.getAnalysisModel() : resolveAnalysisModel(metadata))
                .analysisMode(mode)
                .status(GovernancePreviewItemStatus.FAILED)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now());
        return builder.build();
    }

    private String resolveAnalysisModel(FileMetadata metadata) {
        String model = isImage(metadata.getFileType())
                ? visionModelService.modelName()
                : tagGenerationTool.modelName();
        return model == null || model.isBlank() ? properties.getAnalysisModel() : model;
    }

    private MinioStorageService.ObjectSnapshot statSource(FileMetadata metadata) {
        if (metadata.getStoragePath() == null || metadata.getStoragePath().isBlank()) {
            throw new PreviewAnalysisException("SOURCE_PATH_MISSING", "文件没有可读取的对象路径");
        }
        try {
            return minioStorageService.statFile(null, metadata.getStoragePath());
        } catch (RuntimeException e) {
            throw new PreviewAnalysisException("SOURCE_OBJECT_UNAVAILABLE", safeMessage(e), e);
        }
    }

    private void applySourceSnapshot(GovernancePreviewItem item, FileMetadata metadata,
                                     MinioStorageService.ObjectSnapshot snapshot) {
        if (snapshot == null) {
            throw new PreviewAnalysisException("SOURCE_OBJECT_UNAVAILABLE", "无法读取文件对象快照");
        }
        item.setSourceEtag(snapshot.etag());
        item.setSourceSize(snapshot.size());
    }

    private GovernancePreviewBatchStatus resolveBatchStatus(int total, int ready, int failed, int conflicted) {
        if (ready == total && conflicted == 0) {
            return GovernancePreviewBatchStatus.READY;
        }
        if (ready > 0) {
            return GovernancePreviewBatchStatus.PARTIAL_READY;
        }
        return GovernancePreviewBatchStatus.FAILED;
    }

    private void assertSameRequest(GovernancePreviewBatch existing, List<Long> requestedFileIds,
                                   GovernancePreviewSource source, GovernanceRunMode mode) {
        if (existing.getSource() != source || existing.getRunMode() != mode) {
            throw new IllegalArgumentException("预览请求幂等键已用于不同的来源或运行模式");
        }
        List<Long> existingFileIds = itemRepository.findByPreviewIdOrderByIdAsc(existing.getPreviewId()).stream()
                .map(GovernancePreviewItem::getFileId)
                .sorted()
                .toList();
        if (!existingFileIds.equals(requestedFileIds)) {
            throw new IllegalArgumentException("预览请求幂等键已用于不同的文件集合");
        }
    }

    private void expireIfNecessary(GovernancePreviewBatch batch, LocalDateTime now) {
        if (batch.getExpiresAt() == null || now.isBefore(batch.getExpiresAt())
                || !EXPIRABLE_BATCH_STATUSES.contains(batch.getStatus())) {
            return;
        }
        batch.setStatus(GovernancePreviewBatchStatus.EXPIRED);
        batch.setUpdatedAt(now);
        batchRepository.save(batch);
        List<GovernancePreviewItem> items = itemRepository.findByPreviewIdOrderByIdAsc(batch.getPreviewId());
        boolean changed = false;
        for (GovernancePreviewItem item : items) {
            if (EXPIRABLE_ITEM_STATUSES.contains(item.getStatus())) {
                item.setStatus(GovernancePreviewItemStatus.EXPIRED);
                item.setUpdatedAt(now);
                changed = true;
            }
        }
        if (changed) {
            itemRepository.saveAll(items);
        }
    }

    private GovernancePreviewBatch requireBatch(String previewId) {
        if (previewId == null || previewId.isBlank()) {
            throw new IllegalArgumentException("previewId 不能为空");
        }
        return batchRepository.findByPreviewId(previewId)
                .orElseThrow(() -> new com.coffer.auth.service.ResourceNotFoundException());
    }

    private GovernancePreviewBatch requireBatchForUpdate(String previewId) {
        if (previewId == null || previewId.isBlank()) throw new IllegalArgumentException("previewId 不能为空");
        return batchRepository.findByPreviewIdForUpdate(previewId)
                .or(() -> batchRepository.findByPreviewId(previewId))
                .orElseThrow(() -> new com.coffer.auth.service.ResourceNotFoundException());
    }

    private GovernancePreviewResponse toResponse(GovernancePreviewBatch batch) {
        List<GovernancePreviewItemResponse> items = itemRepository
                .findByPreviewIdOrderByIdAsc(batch.getPreviewId()).stream()
                .map(this::toItemResponse)
                .toList();
        ArchiveOperationBatchResponse latestOperation = archiveOperationService == null
                ? null : archiveOperationService.findLatestResponseByPreviewId(batch.getPreviewId());
        return new GovernancePreviewResponse(
                batch.getPreviewId(), batch.getSource(), batch.getRunMode(), batch.getRequestId(),
                batch.getCreatedBy(), batch.getStatus(), batch.getTotalCount(), batch.getReadyCount(),
                batch.getFailedCount(), batch.getCreatedAt(), batch.getUpdatedAt(), batch.getExpiresAt(),
                batch.getConfirmedAt(), batch.getCancelledAt(), latestOperation, items);
    }

    private String confirmationRequestId(String previewId, ConfirmGovernancePreviewRequest request,
                                          List<Long> selectedIds) {
        if (request.getRequestId() != null && !request.getRequestId().isBlank()) {
            return request.getRequestId().trim();
        }
        String stableInput = previewId + ":" + request.isConfirmAll() + ":" + selectedIds;
        return "confirm-" + UUID.nameUUIDFromBytes(stableInput.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private GovernancePreviewItemResponse toItemResponse(GovernancePreviewItem item) {
        return new GovernancePreviewItemResponse(
                item.getId(), item.getPreviewId(), item.getFileId(), item.getSourceRevision(),
                item.getSourcePath(), item.getSourceFileName(), item.getSourceCategory(), item.getSourceEtag(),
                item.getSourceSize(), item.getSuggestedFileName(), item.getSuggestedCategory(),
                item.getSuggestedPath(), item.getSuggestedSummary(), readTags(item.getSuggestedTags()),
                item.getAnalysisModel(), item.getAnalysisMode(), item.getStatus(), item.getSkipReason(),
                item.getErrorCode(), item.getErrorMessage(), item.getCreatedAt(), item.getUpdatedAt());
    }

    private List<Long> normalizeFileIds(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            throw new IllegalArgumentException("至少选择一个文件生成预览");
        }
        List<Long> normalized = fileIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("文件 ID 不能为空");
        }
        return normalized;
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private Long defaultRevision(FileMetadata metadata) {
        return metadata.getRevision() == null ? 0L : metadata.getRevision();
    }

    private String categoryName(CategoryType category) {
        return category == null ? null : category.name();
    }

    private boolean sameSnapshot(MinioStorageService.ObjectSnapshot before,
                                 MinioStorageService.ObjectSnapshot after) {
        return before != null && after != null
                && Objects.equals(before.etag(), after.etag())
                && before.size() == after.size();
    }

    private String writeTags(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(normalizeTags(tags));
        } catch (JsonProcessingException e) {
            throw new PreviewAnalysisException("TAGS_SERIALIZATION_FAILED", "建议标签序列化失败", e);
        }
    }

    private List<String> readTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            log.warn("预览建议标签 JSON 读取失败");
            return List.of();
        }
    }

    private List<String> normalizeTags(Collection<String> tags) {
        if (tags == null) {
            return List.of();
        }
        return new ArrayList<>(tags.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
    }

    private String limitSummary(String summary) {
        if (summary == null) {
            return "";
        }
        return summary.length() <= SUMMARY_MAX_LENGTH
                ? summary : summary.substring(0, SUMMARY_MAX_LENGTH);
    }

    private String safeMessage(Exception e) {
        if (e instanceof PreviewAnalysisException analysisError) {
            if (analysisError.code.startsWith("PARSE_")) {
                return "文件解析失败，请检查文件格式或内容";
            }
            return switch (analysisError.code) {
                case "NO_TAGS" -> "AI 未生成有效标签";
                case "AI_RESULT_EMPTY" -> "AI 未返回有效分析结果";
                case "SOURCE_PATH_MISSING" -> "文件没有可读取的对象路径";
                case "SOURCE_OBJECT_UNAVAILABLE" -> "无法读取文件对象，请检查存储状态";
                default -> "文件分析失败，请稍后重试";
            };
        }
        if (e instanceof IllegalArgumentException) {
            return "预览请求无效，请检查输入后重试";
        }
        return "文件分析失败，请稍后重试";
    }

    private boolean isImage(String fileType) {
        return fileType != null && IMAGE_EXTENSIONS.contains(fileType.toLowerCase(java.util.Locale.ROOT));
    }

    private String imageMime(String fileType) {
        if (fileType == null) {
            return "image/png";
        }
        return switch (fileType.toLowerCase(java.util.Locale.ROOT)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            default -> "image/png";
        };
    }

    private record AnalysisResult(CategoryType category, List<String> tags, String summary) {
    }

    private static class PreviewAnalysisException extends RuntimeException {
        private final String code;

        private PreviewAnalysisException(String code, String message) {
            super(message);
            this.code = code;
        }

        private PreviewAnalysisException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }
    }
}
