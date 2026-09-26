package com.coffer.service;

import com.coffer.config.MinioConfig;
import io.minio.CopyObjectArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.InvalidKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MinioStorageService#copyObject} 单元测试（纯 Mockito，不启 Spring）：
 * 复制参数正确、source==target 幂等跳过、失败包装 RuntimeException。
 */
class MinioStorageServiceTest {
    @org.junit.jupiter.api.AfterEach void clearOwner() { com.coffer.auth.service.TenantContext.clear(); }

    private MinioClient minioClient;
    private MinioStorageService service;

    @BeforeEach
    void setUp() {
        com.coffer.auth.service.TenantContext.set(7L);
        minioClient = mock(MinioClient.class);
        MinioConfig.MinioProperties props = new MinioConfig.MinioProperties();
        props.setBucketName("coffer-bucket");
        service = new MinioStorageService(minioClient, props);
    }

    @Test
    void copyObjectCopiesToTargetWithDefaultBucket() throws Exception {
        service.copyObject("users/7/files/old.pdf", "users/7/archive/contracts/2025/08/29/new.pdf");

        ArgumentCaptor<CopyObjectArgs> captor = ArgumentCaptor.forClass(CopyObjectArgs.class);
        verify(minioClient).copyObject(captor.capture());
        CopyObjectArgs args = captor.getValue();
        assertThat(args.bucket()).isEqualTo("coffer-bucket");
        assertThat(args.object()).isEqualTo("users/7/archive/contracts/2025/08/29/new.pdf");
        assertThat(args.source().bucket()).isEqualTo("coffer-bucket");
        assertThat(args.source().object()).isEqualTo("users/7/files/old.pdf");
    }

    @Test
    void copyObjectIsIdempotentWhenSourceEqualsTarget() throws Exception {
        service.copyObject("users/7/files/same.pdf", "users/7/files/same.pdf");

        verify(minioClient, never()).copyObject(any(CopyObjectArgs.class));
    }

    @Test
    void copyObjectFailureWrapsRuntimeException() throws Exception {
        // MinIO checked 异常（InvalidKeyException 在 catch 列表内）被包装为 RuntimeException
        when(minioClient.copyObject(any(CopyObjectArgs.class)))
                .thenThrow(new InvalidKeyException("invalid key"));

        assertThatThrownBy(() -> service.copyObject("users/7/files/a.pdf", "users/7/archive/contracts/b.pdf"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("复制失败");
    }

    @Test
    void copyObjectBlankSourceThrows() {
        assertThatThrownBy(() -> service.copyObject("  ", "users/7/archive/contracts/b.pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("源对象名不能为空");
        assertThatThrownBy(() -> service.copyObject("users/7/files/a.pdf", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("目标对象名不能为空");
    }
}
