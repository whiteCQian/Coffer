package com.coffer.governance.api.dto;

import com.coffer.governance.domain.GovernancePreviewBatchStatus;
import com.coffer.governance.domain.GovernancePreviewSource;
import com.coffer.governance.domain.GovernanceRunMode;

import java.time.LocalDateTime;
import java.util.List;

/** API representation of a complete dry-run preview batch. */
public record GovernancePreviewResponse(
        String previewId,
        GovernancePreviewSource source,
        GovernanceRunMode mode,
        String requestId,
        String createdBy,
        GovernancePreviewBatchStatus status,
        int totalCount,
        int readyCount,
        int failedCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime expiresAt,
        LocalDateTime confirmedAt,
        LocalDateTime cancelledAt,
        ArchiveOperationBatchResponse latestOperation,
        List<GovernancePreviewItemResponse> items
) {
}
