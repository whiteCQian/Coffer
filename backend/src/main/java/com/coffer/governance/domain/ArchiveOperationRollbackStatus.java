package com.coffer.governance.domain;

/** Rollback aggregate status, kept independent from execution status. */
public enum ArchiveOperationRollbackStatus {
    NOT_REQUESTED,
    PENDING,
    RUNNING,
    SUCCEEDED,
    PARTIAL_FAILED,
    FAILED
}
