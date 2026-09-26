package com.coffer.auth.service;

import org.springframework.http.HttpStatus;

public final class AuthFailureException extends RuntimeException {
    private final HttpStatus status;

    public AuthFailureException(HttpStatus status, String safeMessage) {
        super(safeMessage);
        this.status = status;
    }

    public HttpStatus status() { return status; }
}
