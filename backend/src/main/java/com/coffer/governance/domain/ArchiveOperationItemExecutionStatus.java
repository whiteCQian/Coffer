package com.coffer.governance.domain;

/** Execution status for one archive operation item. */
public enum ArchiveOperationItemExecutionStatus {
    PENDING,
    VALIDATING,
    COPYING,
    DB_COMMITTING,
    CLEANUP_PENDING,
    SUCCEEDED,
    FAILED,
    CONFLICTED,
    SKIPPED
}
