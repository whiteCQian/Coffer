package com.coffer.governance.api;

import com.coffer.dto.Result;
import com.coffer.governance.api.dto.ArchiveOperationBatchSummaryResponse;
import com.coffer.governance.api.dto.ArchiveOperationBatchResponse;
import com.coffer.governance.api.dto.ArchiveOperationItemResponse;
import com.coffer.governance.application.ArchiveOperationQueryService;
import com.coffer.governance.application.ArchiveOperationService;
import com.coffer.governance.application.ArchiveRollbackService;
import com.coffer.governance.application.GovernanceCompensationRegistry;
import com.coffer.governance.api.dto.GovernanceCompensationTaskResponse;
import com.coffer.governance.domain.ArchiveOperationBatchStatus;
import com.coffer.governance.domain.ArchiveOperationItemExecutionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Query and retry endpoints for confirmed archive execution batches. */
@RestController
@RequestMapping("/api/governance/operations")
@RequiredArgsConstructor
public class ArchiveOperationController {

    private final ArchiveOperationService archiveOperationService;
    private final ArchiveOperationQueryService archiveOperationQueryService;
    private final ArchiveRollbackService archiveRollbackService;
    private final GovernanceCompensationRegistry compensationRegistry;

    @GetMapping
    public Result<Page<ArchiveOperationBatchSummaryResponse>> searchBatches(
            @RequestParam(required = false) String batchId,
            @RequestParam(required = false) Long fileId,
            @RequestParam(required = false) ArchiveOperationBatchStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = org.springframework.data.domain.Sort.Direction.DESC)
            Pageable pageable) {
        return Result.success(archiveOperationQueryService.searchBatches(batchId, fileId, status, pageable));
    }

    @GetMapping("/items")
    public Result<Page<ArchiveOperationItemResponse>> searchItems(
            @RequestParam(required = false) String batchId,
            @RequestParam(required = false) Long fileId,
            @RequestParam(required = false) ArchiveOperationItemExecutionStatus status,
            @PageableDefault(size = 50, sort = "createdAt", direction = org.springframework.data.domain.Sort.Direction.DESC)
            Pageable pageable) {
        return Result.success(archiveOperationQueryService.searchItems(batchId, fileId, status, pageable));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String batchId,
            @RequestParam(required = false) Long fileId,
            @RequestParam(required = false) ArchiveOperationItemExecutionStatus status,
            @RequestParam(defaultValue = "json") String format) {
        ArchiveOperationQueryService.ExportedOperationLedger exported = archiveOperationQueryService
                .export(batchId, fileId, status, format);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(exported.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(exported.fileName(), StandardCharsets.UTF_8)
                .build());
        return ResponseEntity.ok().headers(headers).body(exported.content());
    }

    @GetMapping("/{batchId}")
    public Result<ArchiveOperationBatchResponse> get(@PathVariable String batchId) {
        return Result.success(archiveOperationService.get(batchId));
    }

    @PostMapping("/{batchId}/retry")
    public Result<ArchiveOperationBatchResponse> retry(@PathVariable String batchId) {
        return Result.success(archiveOperationService.retry(batchId));
    }

    @PostMapping("/{batchId}/rollback")
    public Result<ArchiveOperationBatchResponse> rollbackBatch(@PathVariable String batchId) {
        archiveRollbackService.requestBatch(batchId);
        return Result.success(archiveOperationService.get(batchId));
    }

    @PostMapping("/{batchId}/items/{itemId}/rollback")
    public Result<ArchiveOperationBatchResponse> rollbackItem(@PathVariable String batchId, @PathVariable Long itemId) {
        archiveRollbackService.requestItem(batchId, itemId);
        return Result.success(archiveOperationService.get(batchId));
    }

    @GetMapping("/{batchId}/compensations")
    public Result<List<GovernanceCompensationTaskResponse>> compensations(@PathVariable String batchId) {
        return Result.success(compensationRegistry.list(batchId));
    }

    @PostMapping("/{batchId}/compensations/retry")
    public Result<List<GovernanceCompensationTaskResponse>> retryCompensations(@PathVariable String batchId) {
        compensationRegistry.retryBatch(batchId);
        return Result.success(compensationRegistry.list(batchId));
    }
}
