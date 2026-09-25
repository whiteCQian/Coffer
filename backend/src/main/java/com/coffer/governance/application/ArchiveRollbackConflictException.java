package com.coffer.governance.application;

/** A rollback stopped because current user data no longer matches the archived snapshot. */
public class ArchiveRollbackConflictException extends RuntimeException {
    public ArchiveRollbackConflictException(String message) {
        super(message);
    }
}
