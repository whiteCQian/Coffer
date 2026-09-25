package com.coffer.governance.application;

/** Published after a confirmed operation batch has been persisted. */
public record ArchiveOperationRequested(String batchId) {
}
