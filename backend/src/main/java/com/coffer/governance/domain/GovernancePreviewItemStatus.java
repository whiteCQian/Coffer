package com.coffer.governance.domain;

/** Lifecycle of one file-level governance preview item. */
public enum GovernancePreviewItemStatus {
    PENDING,
    READY,
    EDITED,
    CONFLICTED,
    CONFIRMED,
    SKIPPED,
    FAILED,
    EXPIRED,
    CANCELLED
}
