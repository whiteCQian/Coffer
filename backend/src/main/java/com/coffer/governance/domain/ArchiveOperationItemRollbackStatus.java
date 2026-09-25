package com.coffer.governance.domain;

/** Rollback status for one archive operation item. */
public enum ArchiveOperationItemRollbackStatus {
    NOT_REQUESTED,
    PENDING,
    VALIDATING,
    COPYING,
    DB_COMMITTING,
    CLEANUP_PENDING,
    SUCCEEDED,
    FAILED,
    CONFLICTED,
    NOT_REVERSIBLE
}
