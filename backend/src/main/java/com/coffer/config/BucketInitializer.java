package com.coffer.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.GetBucketVersioningArgs;
import io.minio.SetBucketVersioningArgs;
import io.minio.messages.VersioningConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

/**
 * MinIO 存储桶初始化器。
 *
 * <p>应用启动就绪后创建新的版本化存储桶；已有存储桶只检查，不静默改变旧数据配置。
 * 连接、权限及版本配置异常会由存储健康检查报告为 DOWN，写入和物理删除也会拒绝执行。
 */
@Slf4j
@Component
@Profile("!desktop")
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
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
                log.info("MinIO 存储桶已存在");
            } else {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(bucketName)
                        .region("us-east-1")
                        .build());
                minioClient.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(bucketName)
                        .config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED, false))
                        .build());
                log.info("MinIO 存储桶创建成功");
            }
            var versioning = minioClient.getBucketVersioning(GetBucketVersioningArgs.builder()
                    .bucket(bucketName).build());
            if (versioning == null || versioning.status() != VersioningConfiguration.Status.ENABLED)
                throw new IllegalStateException("MinIO 存储桶未启用版本控制；不能安全地写入或物理删除对象");
        } catch (Exception e) {
            log.error("MinIO 存储桶初始化失败（不阻止应用启动），异常类型={}", e.getClass().getSimpleName());
        }
    }
}
