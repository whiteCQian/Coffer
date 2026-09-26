package com.coffer.aspect;

import com.coffer.entity.ModelCallLog;
import com.coffer.service.ModelCallLogSaver;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 模型调用日志切面：拦截所有调用 DeepSeek 模型的方法，记录 Token 消耗与重试信息。
 *
 * <p>切点覆盖标注了 {@link com.coffer.annotation.LogModelCall} 的方法。
 * 重试期间同一线程持有 {@link RetryContext}（经 {@link RetrySynchronizationManager} 读取），
 * 据此记录每次重试日志与 {@code retryCount} 字段；日志通过 {@link ModelCallLogSaver} 异步持久化，
 * 保存失败不影响主流程。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ModelCallLogger {

    /** 配置的重试次数上限（RetryConfig 中 SimpleRetryPolicy maxAttempts=3，即最多重试 2 次）。 */
    private static final int MAX_RETRIES = 2;

    private final ModelCallLogSaver modelCallLogSaver;

    /** 是否开启模型调用日志记录，可通过 application.yml 中 coffer.model-log.enabled 控制。 */
    @Value("${coffer.model-log.enabled:false}")
    private boolean enabled;

    /**
     * 切点：标注 @LogModelCall 的方法。
     */
    @Pointcut("@annotation(com.coffer.annotation.LogModelCall)")
    public void modelCallPointcut() {
    }

    /**
     * 环绕通知：记录开始时间、读取重试上下文、提取 Token 统计、计算耗时并异步保存诊断数据。
     * 重试时按 retryCount 打印每次重试日志；无论成功或抛异常都会在 finally 中落库。
     *
     * @param pjp 连接点
     * @return 原方法返回值
     * @throws Throwable 原方法抛出的异常原样透传
     */
    @Around("modelCallPointcut()")
    public Object logModelCall(ProceedingJoinPoint pjp) throws Throwable {
        if (!enabled) {
            return pjp.proceed();
        }

        long start = System.currentTimeMillis();
        // Content and session identifiers are deliberately excluded from diagnostics.
        // They can identify a user or contain text copied from private files.
        // 重试期间同线程持有 RetryContext，getRetryCount() 即当前调用处于第几次重试
        RetryContext retryContext = RetrySynchronizationManager.getContext();
        int retryCount = retryContext != null ? retryContext.getRetryCount() : 0;

        Object result = null;
        Throwable thrown = null;
        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable t) {
            thrown = t;
            logRetry(retryCount, t);
            throw t;
        } finally {
            try {
                long cost = System.currentTimeMillis() - start;
                ModelCallLog callLog = buildLog(pjp, result, thrown, retryCount, cost);
                modelCallLogSaver.saveAsync(callLog);
            } catch (Exception e) {
                log.error("模型调用诊断记录失败，异常类型={}", e.getClass().getSimpleName());
            }
        }
    }

    /**
     * 打印重试日志：首次失败 warn，第 N 次重试失败按 retryCount 打印，
     * 重试耗尽（retryCount 达到上限）时打印最终失败错误日志。
     */
    private void logRetry(int retryCount, Throwable t) {
        String detail = t.getClass().getSimpleName();
        if (retryCount > 0) {
            log.warn("模型调用失败，第 {} 次重试，异常: {}", retryCount, detail);
            if (retryCount >= MAX_RETRIES) {
                log.error("模型调用重试 {} 次后仍失败，异常: {}", MAX_RETRIES, detail);
            }
        } else {
            log.warn("模型调用失败，异常: {}", detail);
        }
    }

    /**
     * 从返回值 / 异常中提取 Token 统计信息并构建日志实体。
     */
    private ModelCallLog buildLog(ProceedingJoinPoint pjp, Object result, Throwable thrown,
                                  int retryCount, long cost) {
        ModelCallLog.ModelCallLogBuilder builder = ModelCallLog.builder()
                .retryCount(retryCount)
                .callTime(LocalDateTime.now())
                .responseTimeMs(cost);

        if (thrown != null) {
            builder.status("FAILED")
                    .errorMessage(thrown.getClass().getSimpleName())
                    .modelName(null);
            return builder.build();
        }

        builder.status("SUCCESS");
        if (result instanceof ChatResponse response) {
            ChatResponseMetadata metadata = response.metadata();
            TokenUsage usage = metadata != null ? metadata.tokenUsage() : null;
            if (usage == null) {
                usage = response.tokenUsage();
            }
            if (metadata != null) {
                builder.modelName(null);
            }
            if (usage != null) {
                builder.promptTokens(nullSafe(usage.inputTokenCount()))
                        .completionTokens(nullSafe(usage.outputTokenCount()))
                        .totalTokens(nullSafe(usage.totalTokenCount()));
            }
        } else {
            // 返回值不是 ChatResponse（如 chat(String) 直接返回 String），无 Token 统计
            builder.modelName(null);
        }
        log.debug("模型调用完成 method={}, 耗时 {}ms", pjp.getSignature().getName(), cost);
        return builder.build();
    }

    private int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

}
