package com.coffer.governance.application;

/** A rollback cannot be performed because a required file or snapshot no longer exists. */
public class ArchiveRollbackNotReversibleException extends RuntimeException {
    public ArchiveRollbackNotReversibleException(String message) {
        super(message);
    }
}
