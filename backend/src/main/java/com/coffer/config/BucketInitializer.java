package com.coffer.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * MinIO 存储桶初始化器。
 *
 * <p>应用启动就绪后检查默认存储桶是否存在，不存在则创建，确保后续上传可用。
 * 连接、权限等异常仅记录日志，不阻止应用启动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BucketInitializer implements ApplicationListener<ApplicationReadyEvent> {

    private final MinioClient minioClient;
    private final MinioConfig.MinioProperties minioProperties;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        String bucketName = minioProperties.getBucketName();
        if (bucketName == null || bucketName.isBlank()) {
            log.warn("minio.bucket-name 未配置，跳过存储桶初始化");
            return;
        }
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucketName).build());
            if (exists) {
                log.info("MinIO 存储桶已存在: {}", bucketName);
            } else {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(bucketName)
                        .region("us-east-1")
                        .build());
                log.info("MinIO 存储桶创建成功: {}", bucketName);
            }
        } catch (Exception e) {
            log.error("MinIO 存储桶初始化失败: bucket={}, 原因: {}（不阻止应用启动）",
                    bucketName, e.getMessage(), e);
        }
    }
}
