package com.coffer.service;

import com.coffer.annotation.LogModelCall;
import com.coffer.exception.RateLimitException;
import com.coffer.exception.TimeoutException;
import com.coffer.model.provider.ChatProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 模型调用服务：封装 Chat Provider 实际调用，并将底层异常归类为
 * {@link RateLimitException} / {@link TimeoutException}，供重试机制识别。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelCallService {

    private final ChatProvider chatProvider;

    /**
     * 调用当前 Chat Provider，返回回复文本。
     *
     * @param userMessage 用户消息
     * @param sessionId   保留用于调用链兼容；不写入日志或诊断记录
     * @return 模型回复
     */
    @LogModelCall
    public String callModel(String userMessage, String sessionId) {
        long start = System.currentTimeMillis();
        try {
            String reply = chatProvider.chat(userMessage);
            log.info("模型调用成功，耗时 {}ms", System.currentTimeMillis() - start);
            return reply;
        } catch (Exception e) {
            log.warn("模型调用异常，类型={}", e.getClass().getSimpleName());
            throw translateException(e);
        }
    }

    /**
     * 将模型 API 返回的特定错误码或异常类型归类为可重试的自定义异常。
     * 429 -> {@link RateLimitException}；超时/连接 -> {@link TimeoutException}；
     * 其余异常原样透传（不触发重试）。
     */
    private RuntimeException translateException(Exception e) {
        // 1) LangChain4j 已分类的限流 / 超时异常
        if (e instanceof dev.langchain4j.exception.RateLimitException) {
            return new RateLimitException("模型 API 限流(429)", e);
        }
        if (e instanceof dev.langchain4j.exception.TimeoutException) {
            return new TimeoutException("模型 API 调用超时", e);
        }
        // 2) 通用 HTTP 异常按状态码识别
        if (e instanceof dev.langchain4j.exception.HttpException httpException) {
            int status = httpException.statusCode();
            if (status == 429) {
                return new RateLimitException("模型 API 限流(429)", e);
            }
            if (status == 408 || status == 502 || status == 503 || status == 504) {
                return new TimeoutException("模型 API 超时或服务不可用(HTTP " + status + ")", e);
            }
        }
        // 3) 消息启发式（覆盖 SocketTimeout / Connect 等未被分类的异常）
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (message.contains("429") || message.contains("rate limit") || message.contains("too many requests")) {
                return new RateLimitException("模型 API 限流(429)", e);
        }
        if (message.contains("timed out") || message.contains("timeout") || message.contains("connect")
                || message.contains("refused") || message.contains("connection")) {
            return new TimeoutException("模型 API 超时或连接异常", e);
        }
        // 4) 其余异常不归类，原样透传（不触发重试）
        log.warn("模型调用未归类异常，类型={}", e.getClass().getSimpleName());
        return e instanceof RuntimeException runtimeException ? runtimeException
                : new RuntimeException("模型调用失败", e);
    }
}
