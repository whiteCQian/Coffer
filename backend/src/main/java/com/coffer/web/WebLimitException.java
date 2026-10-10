package com.coffer.web;

/** Only fixed public messages; never include paths, owner IDs or submitted content. */
public final class WebLimitException extends RuntimeException {
    private final int status;
    public WebLimitException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
}
