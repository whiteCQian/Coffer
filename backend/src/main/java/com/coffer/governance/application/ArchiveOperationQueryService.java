package com.coffer.governance.application;

import com.coffer.governance.api.dto.ArchiveOperationBatchSummaryResponse;
import com.coffer.governance.api.dto.ArchiveOperationItemResponse;
import com.coffer.governance.domain.ArchiveOperationBatch;
import com.coffer.governance.domain.ArchiveOperationBatchStatus;
import com.coffer.governance.domain.ArchiveOperationItem;
import com.coffer.governance.domain.ArchiveOperationItemExecutionStatus;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationBatchRepository;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Read-only query and export application service for the C08 operation ledger. */
@Service
@RequiredArgsConstructor
public class ArchiveOperationQueryService {

    private static final int MAX_EXPORT_ROWS = 10_000;

    private final ArchiveOperationBatchRepository batchRepository;
    private final ArchiveOperationItemRepository itemRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Page<ArchiveOperationBatchSummaryResponse> searchBatches(String batchId,
                                                                     Long fileId,
                                                                     ArchiveOperationBatchStatus status,
                                                                     Pageable pageable) {
        return batchRepository.search(normalize(batchId), fileId, status, pageable)
                .map(this::toBatchSummary);
    }

    @Transactional(readOnly = true)
    public Page<ArchiveOperationItemResponse> searchItems(String batchId,
                                                           Long fileId,
                                                           ArchiveOperationItemExecutionStatus status,
                                                           Pageable pageable) {
        return itemRepository.search(normalize(batchId), fileId, status, pageable)
                .map(this::toItemResponse);
    }

    @Transactional(readOnly = true)
    public ExportedOperationLedger export(String batchId,
                                          Long fileId,
                                          ArchiveOperationItemExecutionStatus status,
                                          String format) {
        String normalizedFormat = format == null || format.isBlank()
                ? "json" : format.trim().toLowerCase(Locale.ROOT);
        if (!normalizedFormat.equals("json") && !normalizedFormat.equals("csv")) {
            throw new IllegalArgumentException("导出格式仅支持 json 或 csv");
        }
        Pageable pageable = PageRequest.of(0, MAX_EXPORT_ROWS,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ArchiveOperationItemResponse> page = searchItems(batchId, fileId, status, pageable);
        if (page.getTotalElements() > MAX_EXPORT_ROWS) {
            throw new IllegalArgumentException("导出结果超过 " + MAX_EXPORT_ROWS + " 条，请先按批次或文件筛选");
        }
        List<ArchiveOperationItemResponse> items = page.getContent();
        if (normalizedFormat.equals("csv")) {
            return new ExportedOperationLedger(
                    "text/csv;charset=UTF-8",
                    "archive-operations.csv",
                    csv(items));
        }
        try {
            return new ExportedOperationLedger(
                    "application/json;charset=UTF-8",
                    "archive-operations.json",
                    objectMapper.writeValueAsBytes(items));
        } catch (Exception e) {
            throw new IllegalStateException("操作台账 JSON 导出失败", e);
        }
    }

    private ArchiveOperationBatchSummaryResponse toBatchSummary(ArchiveOperationBatch batch) {
        return new ArchiveOperationBatchSummaryResponse(
                batch.getBatchId(), batch.getPreviewId(), batch.getSource(), batch.getRunMode(),
                batch.getRequestId(), batch.getStatus(), batch.getRollbackStatus(), batch.getTotalCount(), batch.getSuccessCount(),
                batch.getFailedCount(), batch.getConflictedCount(), batch.getSkippedCount(),
                batch.getFailureSummary(), batch.getCreatedAt(), batch.getUpdatedAt(),
                batch.getStartedAt(), batch.getFinishedAt(), batch.getRollbackStartedAt(), batch.getRollbackFinishedAt());
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

    private byte[] csv(List<ArchiveOperationItemResponse> items) {
        StringBuilder builder = new StringBuilder("\uFEFF");
        builder.append("batchId,fileId,sourceFileName,targetFileName,sourcePath,targetPath,")
                .append("sourceCategory,targetCategory,executionStatus,executionStep,rollbackStatus,attempts,")
                .append("sourceEtag,targetEtag,preExecuteRevision,postExecuteRevision,")
                .append("failureCode,failureMessage,createdAt,startedAt,finishedAt,rollbackStartedAt,rollbackFinishedAt\n");
        for (ArchiveOperationItemResponse item : items) {
            appendCsvRow(builder,
                    item.batchId(), item.fileId(), item.sourceFileName(), item.targetFileName(),
                    item.sourcePath(), item.targetPath(), item.sourceCategory(), item.targetCategory(),
                    item.executionStatus(), item.executionStep(), item.rollbackStatus(), item.attempts(), item.sourceEtag(),
                    item.targetEtag(), item.preExecuteRevision(), item.postExecuteRevision(),
                    item.failureCode(), item.failureMessage(), item.createdAt(), item.startedAt(),
                    item.finishedAt(), item.rollbackStartedAt(), item.rollbackFinishedAt());
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendCsvRow(StringBuilder builder, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            String value = Objects.toString(values[i], "")
                    .replace("\r", " ")
                    .replace("\n", " ")
                    .replace("\"", "\"\"");
            builder.append('"').append(value).append('"');
        }
        builder.append('\n');
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ExportedOperationLedger(String contentType, String fileName, byte[] content) {
    }
}
