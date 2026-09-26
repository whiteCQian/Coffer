package com.coffer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 模型调用日志相关配置：启用 AOP 代理，
 * 并提供一个专用的日志落库线程池，避免阻塞主请求线程。
 * 异步支持由全局 {@code @EnableAsync}（启动类与 AsyncConfig）统一提供。
 */
@Configuration
@EnableAspectJAutoProxy
public class ModelLogConfig {

    /**
     * 模型调用日志异步保存专用线程池。
     *
     * @return 线程池执行器
     */
    @Bean(name = "modelLogExecutor")
    public ThreadPoolTaskExecutor modelLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("model-log-");
        executor.setTaskDecorator(new TenantContextTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}
