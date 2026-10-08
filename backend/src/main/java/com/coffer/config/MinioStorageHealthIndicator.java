package com.coffer.config;

import io.minio.GetBucketVersioningArgs;
import io.minio.MinioClient;
import io.minio.messages.VersioningConfiguration;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Exposes an unusable server object store as DOWN without revealing credentials. */
@Component("minioStorage")
@org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator("minioStorage")
@Profile("!desktop")
@RequiredArgsConstructor
public class MinioStorageHealthIndicator implements HealthIndicator {
    private final MinioClient client;
    private final MinioConfig.MinioProperties properties;

    @Override
    public Health health() {
        String bucket = properties.getBucketName();
        if (bucket == null || bucket.isBlank())
            return Health.down().withDetail("reason", "BUCKET_NOT_CONFIGURED").build();
        try {
            var versioning = client.getBucketVersioning(GetBucketVersioningArgs.builder()
                    .bucket(bucket).build());
            if (versioning == null || versioning.status() != VersioningConfiguration.Status.ENABLED)
                return Health.down().withDetail("reason", "BUCKET_VERSIONING_DISABLED").build();
            return Health.up().build();
        } catch (Exception failure) {
            return Health.down().withDetail("reason", "BUCKET_UNAVAILABLE").build();
        }
    }
}
