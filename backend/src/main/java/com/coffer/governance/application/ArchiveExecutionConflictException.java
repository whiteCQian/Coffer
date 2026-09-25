package com.coffer.governance.application;

/** A durable execution cannot safely continue because the file changed or a path is occupied. */
public class ArchiveExecutionConflictException extends RuntimeException {

    public ArchiveExecutionConflictException(String message) {
        super(message);
    }
}
