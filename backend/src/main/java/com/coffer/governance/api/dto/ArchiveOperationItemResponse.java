package com.coffer.governance.api.dto;

import com.coffer.governance.domain.ArchiveOperationItemExecutionStatus;
import com.coffer.governance.domain.ArchiveOperationItemExecutionStep;
import com.coffer.governance.domain.ArchiveOperationItemRollbackStatus;

import java.time.LocalDateTime;

/** API representation of one file-level archive execution result. */
public record ArchiveOperationItemResponse(
        Long id,
        String batchId,
        Long fileId,
        Long expectedRevision,
        String sourceFileName,
        String targetFileName,
        String sourceCategory,
        String targetCategory,
        String sourcePath,
        String targetPath,
        String sourceEtag,
        Long sourceSize,
        String targetEtag,
        Long targetSize,
        Long preExecuteRevision,
        Long postExecuteRevision,
        ArchiveOperationItemExecutionStatus executionStatus,
        ArchiveOperationItemExecutionStep executionStep,
        ArchiveOperationItemRollbackStatus rollbackStatus,
        int attempts,
        String failureCode,
        String failureMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime rollbackStartedAt,
        LocalDateTime rollbackFinishedAt
) {
}
