package com.coffer.governance.domain;

/** Source of an archive operation batch. */
public enum ArchiveOperationSource {
    PREVIEW_CONFIRMATION,
    MANUAL_RETRY,
    ROLLBACK_RETRY
}
