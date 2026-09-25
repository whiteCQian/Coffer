package com.coffer.service;

import com.coffer.exception.RateLimitException;
import com.coffer.exception.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * 可重试的模型调用服务：通过 {@link RetryTemplate} 对 DeepSeek 调用自动重试，
 * 重试全部失败后在 {@code RecoveryCallback} 中执行最终降级逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetryableModelService {

    private final RetryTemplate retryTemplate;
    private final ModelCallService modelCallService;

    /**
     * 带重试的模型调用：限流/超时异常自动重试 2 次（共 3 次尝试），
     * 全部失败后返回包含错误信息的降级响应，不向上抛出。
     *
     * @param userMessage 用户消息
     * @param sessionId   会话标识
     * @return 模型回复；重试耗尽时为降级响应
     */
    public String callModelWithRetry(String userMessage, String sessionId) {
        return retryTemplate.execute(
                // RetryCallback：实际模型调用逻辑
                context -> {
                    int attempt = context.getRetryCount() + 1;
                    log.info("模型调用第 {} 次尝试 sessionId={}", attempt, sessionId);
                    return modelCallService.callModel(userMessage, sessionId);
                },
                // RecoveryCallback：所有重试失败后的最终降级
                context -> buildFallbackResponse(context.getLastThrowable())
        );
    }

    /** Executes a provider operation with the shared retry/backoff policy. */
    public <T> T executeWithRetry(String operation, Supplier<T> action) {
        return retryTemplate.execute(context -> {
            log.info("{} 第 {} 次尝试", operation, context.getRetryCount() + 1);
            return action.get();
        });
    }

    /**
     * 构建降级响应：根据最终异常类型返回包含错误信息的默认响应。
     */
    private String buildFallbackResponse(Throwable lastThrowable) {
        if (lastThrowable instanceof RateLimitException) {
            return "（模型服务繁忙，请稍后重试）";
        }
        if (lastThrowable instanceof TimeoutException) {
            return "（模型服务响应超时，请稍后重试）";
        }
        String detail = lastThrowable != null && lastThrowable.getMessage() != null
                ? lastThrowable.getMessage() : "未知错误";
        return "（模型调用失败：" + detail + "）";
    }
}
