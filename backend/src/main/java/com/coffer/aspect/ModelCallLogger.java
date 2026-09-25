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
import org.aspectj.lang.reflect.MethodSignature;
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
    @Value("${coffer.model-log.enabled:true}")
    private boolean enabled;

    /**
     * 切点：标注 @LogModelCall 的方法。
     */
    @Pointcut("@annotation(com.coffer.annotation.LogModelCall)")
    public void modelCallPointcut() {
    }

    /**
     * 环绕通知：记录开始时间、读取重试上下文、提取 Token 统计、计算耗时并异步保存日志。
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
        String sessionId = asString(findArgument(pjp, "sessionId", "session"));
        String userMessage = asString(findArgument(pjp, "userMessage", "message", "query"));
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
                ModelCallLog callLog = buildLog(pjp, sessionId, userMessage, result, thrown, retryCount, cost);
                modelCallLogSaver.saveAsync(callLog);
            } catch (Exception e) {
                log.error("模型调用日志切面处理失败: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 打印重试日志：首次失败 warn，第 N 次重试失败按 retryCount 打印，
     * 重试耗尽（retryCount 达到上限）时打印最终失败错误日志。
     */
    private void logRetry(int retryCount, Throwable t) {
        String detail = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
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
    private ModelCallLog buildLog(ProceedingJoinPoint pjp, String sessionId, String userMessage,
                                  Object result, Throwable thrown, int retryCount, long cost) {
        ModelCallLog.ModelCallLogBuilder builder = ModelCallLog.builder()
                .sessionId(truncate(sessionId, 128))
                .userMessage(truncate(userMessage, 2000))
                .retryCount(retryCount)
                .callTime(LocalDateTime.now())
                .responseTimeMs(cost);

        if (thrown != null) {
            builder.status("FAILED")
                    .errorMessage(truncate(thrown.getMessage(), 2000))
                    .modelName(extractModelName(result));
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
                builder.modelName(truncate(metadata.modelName(), 128));
            }
            if (usage != null) {
                builder.promptTokens(nullSafe(usage.inputTokenCount()))
                        .completionTokens(nullSafe(usage.outputTokenCount()))
                        .totalTokens(nullSafe(usage.totalTokenCount()));
            }
            if (response.aiMessage() != null) {
                builder.aiResponse(truncate(response.aiMessage().text(), 4000));
            }
        } else {
            // 返回值不是 ChatResponse（如 chat(String) 直接返回 String），无 Token 统计
            builder.modelName(extractModelName(result));
        }
        log.debug("模型调用完成 method={}, sessionId={}, 耗时 {}ms", pjp.getSignature().getName(), sessionId, cost);
        return builder.build();
    }

    /**
     * 按参数名查找目标参数值（配合 -parameters 编译；找不到返回 null）。
     */
    private Object findArgument(ProceedingJoinPoint pjp, String... names) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String[] parameterNames = signature.getParameterNames();
        if (parameterNames == null) {
            return null;
        }
        Object[] args = pjp.getArgs();
        for (int i = 0; i < parameterNames.length; i++) {
            for (String name : names) {
                if (name.equals(parameterNames[i])) {
                    return args[i];
                }
            }
        }
        return null;
    }

    private String extractModelName(Object result) {
        if (result instanceof ChatResponse response) {
            ChatResponseMetadata metadata = response.metadata();
            if (metadata != null && metadata.modelName() != null) {
                return truncate(metadata.modelName(), 128);
            }
        }
        return null;
    }

    private String asString(Object obj) {
        return obj == null ? null : String.valueOf(obj);
    }

    private int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
