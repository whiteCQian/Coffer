package com.coffer.exception;

/**
 * DeepSeek API 超时或连接异常（408/502/503/504 / SocketTimeout / Connect），触发自动重试。
 */
public class TimeoutException extends RuntimeException {

    public TimeoutException(String message) {
        super(message);
    }

    public TimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
