package com.coffer.governance.api.dto;

import com.coffer.governance.domain.ArchiveOperationBatchStatus;
import com.coffer.governance.domain.ArchiveOperationSource;
import com.coffer.governance.domain.ArchiveOperationRollbackStatus;
import com.coffer.governance.domain.GovernanceRunMode;

import java.time.LocalDateTime;

/** Lightweight batch row used by the C08 operation ledger list. */
public record ArchiveOperationBatchSummaryResponse(
        String batchId,
        String previewId,
        ArchiveOperationSource source,
        GovernanceRunMode runMode,
        String requestId,
        ArchiveOperationBatchStatus status,
        ArchiveOperationRollbackStatus rollbackStatus,
        int totalCount,
        int successCount,
        int failedCount,
        int conflictedCount,
        int skippedCount,
        String failureSummary,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime rollbackStartedAt,
        LocalDateTime rollbackFinishedAt
) {
}
