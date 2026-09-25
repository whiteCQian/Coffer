package com.coffer.exception;

/**
 * DeepSeek API 限流异常（HTTP 429），触发自动重试。
 */
public class RateLimitException extends RuntimeException {

    public RateLimitException(String message) {
        super(message);
    }

    public RateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
