package com.coffer.config;

import io.minio.GetBucketVersioningArgs;
import io.minio.MinioClient;
import io.minio.messages.VersioningConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MinioStorageHealthIndicatorTest {
    @Test void existingUnversionedBucketIsNotReportedHealthy() throws Exception {
        var client = mock(MinioClient.class);
        var properties = new MinioConfig.MinioProperties();
        properties.setBucketName("coffer-owned-v2");
        when(client.getBucketVersioning(any(GetBucketVersioningArgs.class)))
                .thenReturn(new VersioningConfiguration());
        var health = new MinioStorageHealthIndicator(client, properties).health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "BUCKET_VERSIONING_DISABLED");
    }

    @Test void versionedBucketCanBeReportedHealthy() throws Exception {
        var client = mock(MinioClient.class);
        var properties = new MinioConfig.MinioProperties();
        properties.setBucketName("coffer-owned-v2");
        when(client.getBucketVersioning(any(GetBucketVersioningArgs.class)))
                .thenReturn(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED, false));
        assertThat(new MinioStorageHealthIndicator(client, properties).health().getStatus())
                .isEqualTo(Status.UP);
    }
}
