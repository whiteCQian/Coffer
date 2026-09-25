package com.coffer.exception;

/** Signals a transient embedding-provider failure that should use the shared retry policy. */
public class EmbeddingRetryException extends RuntimeException {

    public EmbeddingRetryException(String message, Throwable cause) {
        super(message, cause);
    }
}
