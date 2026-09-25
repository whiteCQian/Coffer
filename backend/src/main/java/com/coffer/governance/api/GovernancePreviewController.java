package com.coffer.governance.api;

import com.coffer.dto.Result;
import com.coffer.governance.api.dto.ConfirmGovernancePreviewRequest;
import com.coffer.governance.api.dto.CreateGovernancePreviewRequest;
import com.coffer.governance.api.dto.GovernancePreviewResponse;
import com.coffer.governance.api.dto.RegenerateGovernancePreviewRequest;
import com.coffer.governance.api.dto.SkipGovernancePreviewItemRequest;
import com.coffer.governance.api.dto.UpdateGovernancePreviewItemRequest;
import com.coffer.governance.api.dto.ReanalyzeGovernanceFileRequest;
import com.coffer.governance.api.dto.ReanalyzeGovernanceFilesRequest;
import com.coffer.governance.application.GovernancePreviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Dry-run analysis preview and C06 confirmation API; archive execution belongs to C07. */
@RestController
@RequestMapping("/api/governance/previews")
@RequiredArgsConstructor
public class GovernancePreviewController {

    private final GovernancePreviewService governancePreviewService;

    @PostMapping
    public Result<GovernancePreviewResponse> create(@Valid @RequestBody CreateGovernancePreviewRequest request) {
        return Result.success(governancePreviewService.create(request));
    }

    @PostMapping("/reanalyze")
    public Result<GovernancePreviewResponse> reanalyze(
            @Valid @RequestBody ReanalyzeGovernanceFilesRequest request) {
        return Result.success(governancePreviewService.reanalyze(request));
    }

    @PostMapping("/reanalyze/{fileId}")
    public Result<GovernancePreviewResponse> reanalyzeOne(
            @PathVariable Long fileId,
            @Valid @RequestBody ReanalyzeGovernanceFileRequest request) {
        ReanalyzeGovernanceFilesRequest batchRequest = new ReanalyzeGovernanceFilesRequest();
        batchRequest.setFileIds(List.of(fileId));
        batchRequest.setRequestId(request.getRequestId());
        batchRequest.setMode(request.getMode());
        return Result.success(governancePreviewService.reanalyze(batchRequest));
    }

    @GetMapping("/{previewId}")
    public Result<GovernancePreviewResponse> get(@PathVariable String previewId) {
        return Result.success(governancePreviewService.get(previewId));
    }

    @PostMapping("/{previewId}/regenerate")
    public Result<GovernancePreviewResponse> regenerate(
            @PathVariable String previewId,
            @Valid @RequestBody RegenerateGovernancePreviewRequest request) {
        return Result.success(governancePreviewService.regenerate(previewId, request));
    }

    @PatchMapping("/{previewId}/items/{itemId}")
    public Result<GovernancePreviewResponse> updateItem(
            @PathVariable String previewId,
            @PathVariable Long itemId,
            @Valid @RequestBody UpdateGovernancePreviewItemRequest request) {
        return Result.success(governancePreviewService.updateItem(previewId, itemId, request));
    }

    @PostMapping("/{previewId}/items/{itemId}/skip")
    public Result<GovernancePreviewResponse> skipItem(
            @PathVariable String previewId,
            @PathVariable Long itemId,
            @Valid @RequestBody(required = false) SkipGovernancePreviewItemRequest request) {
        return Result.success(governancePreviewService.skipItem(previewId, itemId, request));
    }

    @PostMapping("/{previewId}/confirm")
    public Result<GovernancePreviewResponse> confirm(
            @PathVariable String previewId,
            @Valid @RequestBody ConfirmGovernancePreviewRequest request) {
        return Result.success(governancePreviewService.confirm(previewId, request));
    }

    @PostMapping("/{previewId}/cancel")
    public Result<GovernancePreviewResponse> cancel(@PathVariable String previewId) {
        return Result.success(governancePreviewService.cancel(previewId));
    }
}
