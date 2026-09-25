package com.coffer.governance.application;

/** Starts either a whole-batch rollback or one item after the request transaction commits. */
public record ArchiveRollbackRequested(String batchId, Long itemId) {
}
