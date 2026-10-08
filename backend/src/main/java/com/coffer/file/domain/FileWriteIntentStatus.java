package com.coffer.file.domain;

/** Durable phases of one upload or inbox import. */
public enum FileWriteIntentStatus {
    PREPARED,
    OBJECT_WRITTEN,
    REGISTERED,
    FAILED,
    ABORTED,
    MANUAL_REVIEW,
    DISCARD_PENDING,
    DISCARDING,
    DISCARDED,
    RESOLVED
}
