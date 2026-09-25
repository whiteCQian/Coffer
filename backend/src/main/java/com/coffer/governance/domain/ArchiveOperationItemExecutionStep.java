package com.coffer.governance.domain;

/** Durable execution checkpoint used to resume an interrupted operation. */
public enum ArchiveOperationItemExecutionStep {
    NONE,
    SOURCE_VERIFIED,
    TARGET_COPIED,
    METADATA_COMMITTED,
    OLD_OBJECT_CLEANUP_PENDING,
    COMPLETED
}
