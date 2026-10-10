package com.coffer.service;

import com.coffer.config.MinioConfig;
import com.coffer.file.storage.StorageConflictException;
import io.minio.MinioClient;
import io.minio.ListObjectsArgs;
import io.minio.PutObjectArgs;
import io.minio.GetObjectArgs;
import io.minio.GetBucketVersioningArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.Result;
import io.minio.messages.Item;
import io.minio.messages.VersioningConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The server adapter must never overwrite or delete an object with a mismatched identity. */
class MinioStorageServiceTest {
    @org.junit.jupiter.api.AfterEach void clearOwner() { com.coffer.auth.service.TenantContext.clear(); }
    private MinioClient client;
    private MinioStorageService service;

    @BeforeEach void setUp() throws Exception {
        com.coffer.auth.service.TenantContext.set(7L);
        client = mock(MinioClient.class);
        MinioConfig.MinioProperties properties = new MinioConfig.MinioProperties();
        properties.setBucketName("coffer-bucket");
        service = new MinioStorageService(client, properties);
        when(client.getBucketVersioning(any(GetBucketVersioningArgs.class)))
                .thenReturn(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED, false));
    }

    @Test void existingTargetIsNeverOverwritten() throws Exception {
        Item existing = mock(Item.class);
        when(existing.objectName()).thenReturn("users/7/files/x.txt");
        when(existing.versionId()).thenReturn("version-1");
        @SuppressWarnings("unchecked") Result<Item> listed = mock(Result.class);
        when(listed.get()).thenReturn(existing);
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(java.util.List.of(listed));
        assertThatThrownBy(() -> service.write("users/7/files/x.txt",
                new ByteArrayInputStream(new byte[] { 'x' }), "text/plain", 1))
                .isInstanceOf(StorageConflictException.class);
        verify(client, never()).putObject(any(PutObjectArgs.class));
    }

    @Test void copyToSamePathFailsBeforeReading() {
        assertThatThrownBy(() -> service.copy("users/7/files/x.txt", "users/7/files/x.txt", "a".repeat(64)))
                .isInstanceOf(StorageConflictException.class);
        verifyNoInteractions(client);
    }
    @Test void quotaRejectionPreventsMinioIoAndStagingBeforeAnyObjectWrite() {
        var quota=mock(com.coffer.web.WebLimits.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"webLimits",quota);
        doThrow(new com.coffer.web.WebLimitException(507,"MinIO 持久卷剩余容量不足")).when(quota).reserve("users/7/files/new.txt",1L);
        assertThatThrownBy(()->service.write("users/7/files/new.txt",new ByteArrayInputStream(new byte[]{'x'}),"text/plain",1L))
                .isInstanceOf(com.coffer.web.WebLimitException.class);
        verifyNoInteractions(client);
    }

    @Test void deletionRequiresMatchingSha256() throws Exception {
        var existing = mock(StatObjectResponse.class);
        when(existing.userMetadata()).thenReturn(Map.of("sha256", sha256(new byte[] { 'x' })));
        when(existing.size()).thenReturn(1L);
        when(existing.etag()).thenReturn("etag");
        when(existing.versionId()).thenReturn("version-1");
        when(client.statObject(any(StatObjectArgs.class))).thenReturn(existing);
        when(client.getObject(any(GetObjectArgs.class))).thenAnswer(invocation -> objectResponse());
        assertThatThrownBy(() -> service.delete("users/7/files/x.txt", "b".repeat(64)))
                .isInstanceOf(StorageConflictException.class);
        verify(client, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test void deletionDoesNotClaimSuccessIfAHiddenVersionRemains() throws Exception {
        var existing = mock(StatObjectResponse.class);
        when(existing.userMetadata()).thenReturn(Map.of("sha256", sha256(new byte[] { 'x' })));
        when(existing.size()).thenReturn(1L);
        when(existing.etag()).thenReturn("etag");
        when(existing.versionId()).thenReturn("version-1");
        when(client.statObject(any(StatObjectArgs.class))).thenReturn(existing);
        when(client.getObject(any(GetObjectArgs.class))).thenAnswer(invocation -> objectResponse());
        var original = java.util.List.of(version("users/7/files/x.txt", "version-1", false));
        var hidden = java.util.List.of(version("users/7/files/x.txt", "marker-2", true));
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(
                original, hidden);

        assertThatThrownBy(() -> service.delete("users/7/files/x.txt", sha256(new byte[] { 'x' })))
                .isInstanceOf(StorageConflictException.class);
        verify(client).removeObject(any(RemoveObjectArgs.class));
    }

    @Test void staleSha256MetadataCannotAuthenticateDifferentBytes() throws Exception {
        var existing = mock(StatObjectResponse.class);
        when(existing.userMetadata()).thenReturn(Map.of("sha256", "a".repeat(64)));
        when(existing.size()).thenReturn(1L);
        when(client.statObject(any(StatObjectArgs.class))).thenReturn(existing);
        when(client.getObject(any(GetObjectArgs.class))).thenAnswer(invocation -> objectResponse());

        assertThatThrownBy(() -> service.stat("users/7/files/x.txt"))
                .isInstanceOf(StorageConflictException.class);
    }

    @Test void suspendedBucketNeverAcceptsAnObjectWriteOrPhysicalDelete() throws Exception {
        when(client.getBucketVersioning(any(GetBucketVersioningArgs.class)))
                .thenReturn(new VersioningConfiguration(VersioningConfiguration.Status.SUSPENDED, false));
        assertThatThrownBy(() -> service.write("users/7/files/x.txt",
                new ByteArrayInputStream(new byte[] { 'x' }), "text/plain", 1))
                .isInstanceOf(StorageConflictException.class);
        assertThatThrownBy(() -> service.delete("users/7/files/x.txt", "a".repeat(64)))
                .isInstanceOf(StorageConflictException.class);
        verify(client, never()).putObject(any(PutObjectArgs.class));
        verify(client, never()).removeObject(any(RemoveObjectArgs.class));
    }

    private static String sha256(byte[] data) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static io.minio.GetObjectResponse objectResponse() {
        return new io.minio.GetObjectResponse(new okhttp3.Headers.Builder().build(),
                "coffer-bucket", null, "users/7/files/x.txt", new ByteArrayInputStream(new byte[] { 'x' }));
    }

    private static Result<Item> version(String key, String id, boolean marker) throws Exception {
        Item item = mock(Item.class);
        when(item.objectName()).thenReturn(key);
        when(item.versionId()).thenReturn(id);
        when(item.isDeleteMarker()).thenReturn(marker);
        @SuppressWarnings("unchecked") Result<Item> result = mock(Result.class);
        when(result.get()).thenReturn(item);
        return result;
    }
}
