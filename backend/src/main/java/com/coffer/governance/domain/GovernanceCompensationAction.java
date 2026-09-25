package com.coffer.governance.domain;

/** Recoverable action captured after a partially completed archive or rollback. */
public enum GovernanceCompensationAction {
    RESUME_ARCHIVE,
    DELETE_ARCHIVE_SOURCE,
    RESUME_ROLLBACK,
    DELETE_ROLLBACK_TARGET
}
