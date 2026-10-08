package com.coffer.auth;

import com.coffer.auth.service.*;
import com.coffer.config.MinioConfig;
import com.coffer.config.TenantContextTaskDecorator;
import com.coffer.service.MinioStorageService;
import io.minio.MinioClient;
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageAuthorizationTest {
    @AfterEach void cleanup() { TenantContext.clear(); }

    @Test void everyObjectOperationRejectsForeignOrNonCanonicalPathsBeforeIo() {
        MinioClient client = mock(MinioClient.class);
        MinioConfig.MinioProperties properties = new MinioConfig.MinioProperties();
        properties.setBucketName("private-bucket");
        var storage = new MinioStorageService(client, properties);
        TenantContext.runAs(10L, () -> {
            for (String path : java.util.List.of("users/11/files/x", "users/100/files/x", "files/x",
                    "users/10/../11/files/x", "users/10/files/./x", "users/10/files//x", "users/10/files/\\x")) {
                assertThatThrownBy(() -> storage.read(path)).isInstanceOf(RuntimeException.class);
                assertThatThrownBy(() -> storage.delete(path, "a".repeat(64))).isInstanceOf(RuntimeException.class);
                assertThatThrownBy(() -> storage.stat(path)).isInstanceOf(RuntimeException.class);
                assertThatThrownBy(() -> storage.write(path, java.io.InputStream.nullInputStream(), "text/plain", 0))
                        .isInstanceOf(RuntimeException.class);
                assertThatThrownBy(() -> storage.copy(path, "users/10/files/safe", "a".repeat(64)))
                        .isInstanceOf(RuntimeException.class);
                assertThatThrownBy(() -> storage.copy("users/10/files/safe", path, "a".repeat(64)))
                        .isInstanceOf(RuntimeException.class);
            }
        });
        verifyNoInteractions(client);
    }

    @Test void reusedExecutorDoesNotLeakOwnerOnSuccessOrException() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var decorator = new TenantContextTaskDecorator();
        try {
            for (Long owner : java.util.List.of(10L, 11L)) {
                TenantContext.set(owner);
                Runnable task = decorator.decorate(() -> {
                    assertThat(TenantContext.requireOwnerId()).isEqualTo(owner);
                    throw new IllegalStateException("expected");
                });
                TenantContext.clear();
                assertThatThrownBy(() -> executor.submit(task).get()).isInstanceOf(ExecutionException.class);
                assertThat(executor.submit(TenantContext::currentTenantId).get()).isZero();
            }
            TenantContext.set(10L);
            Runnable callerRuns = decorator.decorate(() -> assertThat(TenantContext.requireOwnerId()).isEqualTo(10L));
            callerRuns.run();
            assertThat(TenantContext.requireOwnerId()).isEqualTo(10L);
        } finally { executor.shutdownNow(); }
    }
}
