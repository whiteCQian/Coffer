package com.coffer.governance.domain;

/** Execution aggregate status for an archive operation batch. */
public enum ArchiveOperationBatchStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    PARTIAL_FAILED,
    FAILED,
    CANCELLED
}
