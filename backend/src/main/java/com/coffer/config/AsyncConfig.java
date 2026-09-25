package com.coffer.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池配置：为文件上传后的自动分析管道提供独立执行器，
 * 避免阻塞主请求线程。
 *
 * <p>{@code @EnableAsync} 开启 Spring 异步方法执行（与 {@link com.coffer.CofferApplication}
 * 上的注解幂等共存）；默认执行器 {@code taskExecutor} 承载
 * {@code @Async("taskExecutor")} 的上传分析管道任务。
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 文件处理管道专用线程池（异步默认执行器）：
     * 核心 2、最大 4、队列 100。
     *
     * <p>核心线程数 2 可并行处理并发上传请求，最大线程数 4 允许高峰期临时扩容，
     * 队列容量 100 缓冲大量等待任务而不会拒绝请求；线程池与队列均满时以
     * {@link ThreadPoolExecutor.CallerRunsPolicy} 由调用线程执行，减缓任务提交速度而非直接拒绝。
     * 应用关闭时等待任务完成，最长 60 秒，避免任务丢失。
     *
     * @return 线程池执行器
     */
    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("coffer-async-");
        // 线程池与队列满时由调用线程执行，减缓任务提交速度，避免拒绝请求
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        log.info("异步线程池已初始化，核心线程数: {}, 最大线程数: {}, 队列容量: {}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        return executor;
    }

    @Bean(name = "vectorIndexExecutor")
    public ThreadPoolTaskExecutor vectorIndexExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("coffer-vector-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    @Bean(name = "vectorSearchExecutor")
    public ThreadPoolTaskExecutor vectorSearchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("coffer-vector-search-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
