package com.coffer.governance.application;

import com.coffer.governance.api.dto.ArchiveOperationBatchSummaryResponse;
import com.coffer.governance.api.dto.ArchiveOperationItemResponse;
import com.coffer.governance.domain.ArchiveOperationBatch;
import com.coffer.governance.domain.ArchiveOperationBatchStatus;
import com.coffer.governance.domain.ArchiveOperationItem;
import com.coffer.governance.domain.ArchiveOperationItemExecutionStatus;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationBatchRepository;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationItemRepository;
import com.coffer.auth.service.OwnerAuthorization;
import com.coffer.auth.service.ResourceNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Read-only query and export application service for the C08 operation ledger. */
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class ArchiveOperationQueryService {

    private static final int MAX_EXPORT_ROWS = 10_000;

    private final ArchiveOperationBatchRepository batchRepository;
    private final ArchiveOperationItemRepository itemRepository;
    private final ObjectMapper objectMapper;
    private final OwnerAuthorization authorization;

    @Value("${coffer.governance.export.max-bytes:8388608}")
    private int maxExportBytes;

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
        LimitedOutput output = new LimitedOutput(maxExportBytes);
        try {
            objectMapper.writeValue(output, items);
            return new ExportedOperationLedger(
                    "application/json;charset=UTF-8",
                    "archive-operations.json",
                    output.toByteArray());
        } catch (Exception e) {
            if (output.exceeded()) throw new IllegalArgumentException("导出内容超过大小上限，请缩小筛选范围", e);
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
        if (!authorization.requireOwner().equals(item.getOwnerId())) throw new ResourceNotFoundException();
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
        LimitedOutput output = new LimitedOutput(maxExportBytes);
        output.append("\uFEFFbatchId,fileId,sourceFileName,targetFileName,sourcePath,targetPath,")
                .append("sourceCategory,targetCategory,executionStatus,executionStep,rollbackStatus,attempts,")
                .append("sourceEtag,targetEtag,preExecuteRevision,postExecuteRevision,")
                .append("failureCode,failureMessage,createdAt,startedAt,finishedAt,rollbackStartedAt,rollbackFinishedAt\n");
        for (ArchiveOperationItemResponse item : items) {
            appendCsvRow(output,
                    item.batchId(), item.fileId(), item.sourceFileName(), item.targetFileName(),
                    item.sourcePath(), item.targetPath(), item.sourceCategory(), item.targetCategory(),
                    item.executionStatus(), item.executionStep(), item.rollbackStatus(), item.attempts(), item.sourceEtag(),
                    item.targetEtag(), item.preExecuteRevision(), item.postExecuteRevision(),
                    item.failureCode(), item.failureMessage(), item.createdAt(), item.startedAt(),
                    item.finishedAt(), item.rollbackStartedAt(), item.rollbackFinishedAt());
        }
        return output.toByteArray();
    }

    private void appendCsvRow(LimitedOutput output, Object... values) {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                row.append(',');
            }
            String value = Objects.toString(values[i], "")
                    .replace("\r", " ")
                    .replace("\n", " ")
                    .replace("\t", " ")
                    .replace("\"", "\"\"");
            // Spreadsheet programs can evaluate a quoted CSV cell as a formula.
            // Prefix text even when whitespace or invisible format characters lead the payload.
            if (startsWithFormula(value)) value = "'" + value;
            row.append('"').append(value).append('"');
        }
        row.append('\n');
        output.append(row.toString());
    }

    private boolean startsWithFormula(String value) {
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            if (!Character.isWhitespace(codePoint)
                    && Character.getType(codePoint) != Character.SPACE_SEPARATOR
                    && Character.getType(codePoint) != Character.FORMAT) break;
            index += Character.charCount(codePoint);
        }
        if (index == value.length()) return false;
        return "=+-@".indexOf(value.charAt(index)) >= 0;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ExportedOperationLedger(String contentType, String fileName, byte[] content) {
    }

    /** Limits encoded bytes while the serializer is writing, before a large response is allocated. */
    private static final class LimitedOutput extends OutputStream {
        private final int maximum;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean exceeded;

        private LimitedOutput(int maximum) {
            if (maximum <= 0) throw new IllegalStateException("导出大小上限必须大于零");
            this.maximum = maximum;
        }

        @Override public void write(int value) throws IOException { check(1); bytes.write(value); }
        @Override public void write(byte[] value, int offset, int length) throws IOException {
            check(length);
            bytes.write(value, offset, length);
        }
        private void check(int length) throws IOException {
            if (length > maximum - bytes.size()) {
                exceeded = true;
                throw new IOException("导出大小超限");
            }
        }
        private LimitedOutput append(String value) {
            try { write(value.getBytes(StandardCharsets.UTF_8)); }
            catch (IOException tooLarge) { throw new IllegalArgumentException("导出内容超过大小上限，请缩小筛选范围", tooLarge); }
            return this;
        }
        private boolean exceeded() { return exceeded; }
        private byte[] toByteArray() { return bytes.toByteArray(); }
    }
}
