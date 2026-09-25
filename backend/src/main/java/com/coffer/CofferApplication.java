package com.coffer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Coffer 应用启动类。
 *
 * <p>位于 com.coffer 根包下，默认组件扫描范围为 com.coffer 及其子包
 * （controller、service、repository、entity、config 等），无需额外配置。
 *
 * <p>{@code @EnableAsync} 全局开启 Spring 异步方法执行（与 {@code AsyncConfig} 上
 * 的注解幂等共存），上传分析管道等 {@code @Async} 方法可正常调度。
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class CofferApplication {

    /**
     * 应用入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(CofferApplication.class, args);
    }
}
