package com.coffer.config;

import io.minio.MinioClient;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 自动配置类。
 *
 * <p>从 {@code minio.*} 配置前缀加载连接信息，并提供 {@link MinioClient} Bean。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(MinioConfig.MinioProperties.class)
public class MinioConfig {

    /**
     * 构建 MinIO 客户端。
     *
     * <p>endpoint 未以 http/https 开头时按 secure 自动补齐协议；secure=true 强制 HTTPS，否则 HTTP。
     *
     * @param properties MinIO 连接配置
     * @return MinIO 客户端
     */
    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        String endpoint = normalizeEndpoint(properties.getEndpoint(), properties.isSecure());
        log.info("初始化 MinIO 客户端: endpoint={}, bucket={}",
                endpoint, properties.getBucketName());
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    /**
     * 规范化 endpoint 地址。
     *
     * <p>缺少协议前缀时补充 http/https（由 secure 决定）；secure=true 时强制将 http 升级为 https。
     *
     * @param endpoint 原始 endpoint
     * @param secure   是否启用 SSL
     * @return 规范化后的 endpoint
     */
    private String normalizeEndpoint(String endpoint, boolean secure) {
        String normalized = endpoint.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = (secure ? "https://" : "http://") + normalized;
        } else if (secure && normalized.startsWith("http://")) {
            normalized = "https://" + normalized.substring("http://".length());
        }
        return normalized;
    }

    /**
     * MinIO 连接配置属性，对应 {@code minio.*} 前缀。
     */
    @Data
    @ConfigurationProperties(prefix = "minio")
    public static class MinioProperties {

        /** MinIO 服务地址。 */
        private String endpoint;

        /** 访问密钥（用户名）。 */
        private String accessKey;

        /** 密钥（密码）。 */
        private String secretKey;

        /** 默认存储桶名称。 */
        private String bucketName;

        /** 是否启用 SSL（HTTPS），默认 false 使用 HTTP。 */
        private boolean secure = false;
    }
}
