package com.coffer.config;

import com.coffer.operations.RuntimeMonitor;
import com.coffer.operations.StorageProbe;
import io.minio.GetBucketVersioningArgs;
import io.minio.MinioClient;
import io.minio.messages.VersioningConfiguration;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component @Profile("!desktop") @RequiredArgsConstructor
public class ServerStorageProbe implements StorageProbe {
    private final MinioClient client;
    private final MinioConfig.MinioProperties properties;
    public RuntimeMonitor.Component check() {
        try {
            var configuration = client.getBucketVersioning(GetBucketVersioningArgs.builder().bucket(properties.getBucketName()).build());
            boolean enabled = configuration != null && configuration.status() == VersioningConfiguration.Status.ENABLED;
            return new RuntimeMonitor.Component("storage", enabled ? "UP" : "DOWN",
                    enabled ? "OK" : "BUCKET_VERSIONING_DISABLED", enabled ? "NONE" : "CHECK_BUCKET_VERSIONING", null, null);
        } catch (Exception failure) {
            return new RuntimeMonitor.Component("storage", "DOWN", "STORAGE_UNAVAILABLE", "CHECK_STORAGE", null, null);
        }
    }
}
