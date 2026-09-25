package com.coffer.config;

import com.coffer.exception.RateLimitException;
import com.coffer.exception.TimeoutException;
import com.coffer.exception.EmbeddingRetryException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.CompositeRetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.policy.TimeoutRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.Map;

/**
 * 模型调用重试配置。
 *
 * <p>对模型限流、超时和 Embedding 提供方异常触发重试，
 * 最大尝试 3 次（含首次，即重试 2 次），指数退避，并设置全局超时防止单次重试无限等待。
 */
@Configuration
@EnableRetry
public class RetryConfig {

    /** 可重试异常白名单：聊天模型限流/超时，以及 Embedding 提供方异常。 */
    private static final Map<Class<? extends Throwable>, Boolean> RETRYABLE_EXCEPTIONS = Map.of(
            RateLimitException.class, Boolean.TRUE,
            TimeoutException.class, Boolean.TRUE,
            EmbeddingRetryException.class, Boolean.TRUE
    );

    /**
     * 重试模板 Bean。
     *
     * <p>说明：builder 的 {@code withTimeout} 会将全局超时以 TimeoutRetryPolicy 写入 baseRetryPolicy，
     * 与 customPolicy 互斥，故将两者放入 AND 语义的 CompositeRetryPolicy 组合，同时满足
     * "未超时" 且 "尝试次数未超限" 才继续重试。
     *
     * @return RetryTemplate
     */
    @Bean
    public RetryTemplate retryTemplate() {
        // 重试策略：最大尝试 3 次（含首次，即重试 2 次），仅白名单异常触发重试
        SimpleRetryPolicy simpleRetryPolicy = new SimpleRetryPolicy(3, RETRYABLE_EXCEPTIONS);
        // 全局超时：整个重试序列最多 30 秒，防止单次重试无限等待
        TimeoutRetryPolicy timeoutRetryPolicy = new TimeoutRetryPolicy(30000);
        CompositeRetryPolicy compositeRetryPolicy = new CompositeRetryPolicy();
        compositeRetryPolicy.setPolicies(new RetryPolicy[]{simpleRetryPolicy, timeoutRetryPolicy});

        // 指数退避：初始 1000ms、乘数 2、最大 10000ms
        ExponentialBackOffPolicy exponentialBackOffPolicy = new ExponentialBackOffPolicy();
        exponentialBackOffPolicy.setInitialInterval(1000);
        exponentialBackOffPolicy.setMultiplier(2);
        exponentialBackOffPolicy.setMaxInterval(10000);

        return RetryTemplate.builder()
                .customPolicy(compositeRetryPolicy)
                .customBackoff(exponentialBackOffPolicy)
                .build();
    }
}
