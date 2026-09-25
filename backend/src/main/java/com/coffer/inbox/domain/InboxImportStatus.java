package com.coffer.inbox.domain;

/** Lifecycle of one immutable inbox file snapshot. */
public enum InboxImportStatus {

    /** The file was seen but has not remained unchanged long enough. */
    DISCOVERED,

    /** The file is stable and waiting to be claimed for import. */
    STABLE,

    /** The snapshot has been claimed and is being copied into object storage. */
    IMPORTING,

    /** The snapshot was registered as a normal asynchronous file task. */
    IMPORTED,

    /** The same content was already imported by another snapshot. */
    DUPLICATE,

    /** The import failed and may be retried after nextAttemptAt. */
    FAILED,

    /** The file type is outside the first-version supported format list. */
    UNSUPPORTED
}
