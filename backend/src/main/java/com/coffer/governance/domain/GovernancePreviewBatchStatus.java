package com.coffer.governance.domain;

/** Lifecycle of a governance preview batch. */
public enum GovernancePreviewBatchStatus {
    ANALYZING,
    READY,
    PARTIAL_READY,
    CONFIRMING,
    PARTIALLY_CONFIRMED,
    CONFIRMED,
    EXPIRED,
    CANCELLED,
    FAILED
}
