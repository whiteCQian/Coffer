package com.coffer.governance.api.dto;

import com.coffer.governance.domain.GovernancePreviewItemStatus;
import com.coffer.governance.domain.GovernanceRunMode;

import java.time.LocalDateTime;
import java.util.List;

/** API representation of one dry-run preview item. */
public record GovernancePreviewItemResponse(
        Long id,
        String previewId,
        Long fileId,
        Long sourceRevision,
        String sourcePath,
        String sourceFileName,
        String sourceCategory,
        String sourceEtag,
        Long sourceSize,
        String suggestedFileName,
        String suggestedCategory,
        String suggestedPath,
        String suggestedSummary,
        List<String> suggestedTags,
        String analysisModel,
        GovernanceRunMode analysisMode,
        GovernancePreviewItemStatus status,
        String skipReason,
        String errorCode,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
