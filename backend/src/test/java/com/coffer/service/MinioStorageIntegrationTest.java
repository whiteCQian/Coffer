package com.coffer.service;

import com.coffer.auth.service.TenantContext;
import com.coffer.config.MinioConfig;
import com.coffer.file.storage.StorageConflictException;
import io.minio.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in real MinIO contract test; runs against an isolated bucket and cleans only that bucket. */
@EnabledIfEnvironmentVariable(named = "COFFER_TEST_MINIO_URL", matches = ".+")
class MinioStorageIntegrationTest {
    private MinioClient client;
    private MinioStorageService storage;
    private String bucket;

    @BeforeEach void prepare() throws Exception {
        client = MinioClient.builder().endpoint(System.getenv("COFFER_TEST_MINIO_URL"))
                .credentials(System.getenv("COFFER_TEST_MINIO_USER"),
                        System.getenv("COFFER_TEST_MINIO_PASSWORD")).build();
        bucket = "coffer-it-" + UUID.randomUUID().toString().substring(0, 16);
        client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        client.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(bucket)
                .config(new io.minio.messages.VersioningConfiguration(
                        io.minio.messages.VersioningConfiguration.Status.ENABLED, false)).build());
        var properties = new MinioConfig.MinioProperties();
        properties.setBucketName(bucket);
        storage = new MinioStorageService(client, properties);
        TenantContext.set(101L);
    }

    @AfterEach void cleanup() throws Exception {
        try {
            if (client != null && bucket != null) {
                for (var result : client.listObjects(ListObjectsArgs.builder()
                        .bucket(bucket).recursive(true).includeVersions(true).build())) {
                    var item = result.get();
                    client.removeObject(RemoveObjectArgs.builder().bucket(bucket)
                            .object(item.objectName()).versionId(item.versionId()).build());
                }
                client.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
            }
        } finally { TenantContext.clear(); }
    }

    @Test void conditionalWriteRangeReadOwnerBoundaryAndDigestDelete() throws Exception {
        String first = "users/101/files/shared.txt";
        byte[] original = "abcdef".getBytes(StandardCharsets.UTF_8);
        var written = storage.write(first, new ByteArrayInputStream(original), "text/plain", original.length);
        assertThat(storage.stat(first).sha256()).isEqualTo(written.sha256());
        try (var range = storage.readRange(first, 2, 3)) {
            assertThat(range.readAllBytes()).isEqualTo("cde".getBytes(StandardCharsets.UTF_8));
        }
        assertThatThrownBy(() -> storage.write(first, new ByteArrayInputStream("replace".getBytes()),
                "text/plain", 7)).isInstanceOf(StorageConflictException.class);
        try (var input = storage.read(first)) { assertThat(input.readAllBytes()).isEqualTo(original); }
        assertThatThrownBy(() -> storage.delete(first, "0".repeat(64)))
                .isInstanceOf(StorageConflictException.class);

        TenantContext.set(102L);
        assertThatThrownBy(() -> storage.read(first))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        String second = "users/102/files/shared.txt";
        storage.write(second, new ByteArrayInputStream(original), "text/plain", original.length);
        TenantContext.set(101L);
        assertThat(storage.listOwnedKeys()).containsExactly(first);
        storage.delete(first, written.sha256());
        assertThat(storage.exists(first)).isFalse();
        assertThatThrownBy(() -> storage.read(first))
                .isInstanceOf(com.coffer.file.storage.StorageObjectNotFoundException.class);
        assertThatThrownBy(() -> storage.readRange(first, 0, 1))
                .isInstanceOf(com.coffer.file.storage.StorageObjectNotFoundException.class);
    }

    @Test void copyAndMoveVerifyBytesAndNeverReplaceAnExistingTarget() throws Exception {
        String source = "users/101/files/source.txt";
        String copy = "users/101/archive/copy.txt";
        String moved = "users/101/archive/moved.txt";
        byte[] bytes = "version-one".getBytes(StandardCharsets.UTF_8);
        var original = storage.write(source, new ByteArrayInputStream(bytes), "text/plain", bytes.length);

        assertThat(storage.copy(source, copy, original.sha256()).sha256()).isEqualTo(original.sha256());
        assertThatThrownBy(() -> storage.copy(source, copy, original.sha256()))
                .isInstanceOf(StorageConflictException.class);
        assertThat(storage.exists(source)).isTrue();
        assertThat(storage.move(copy, moved, original.sha256()).sha256()).isEqualTo(original.sha256());
        assertThat(storage.exists(copy)).isFalse();
        try (var input = storage.read(moved)) { assertThat(input.readAllBytes()).isEqualTo(bytes); }
    }

    @Test void verifiedReadRejectsAnObjectReplacedAfterStat() throws Exception {
        String key = "users/101/files/versioned.txt";
        byte[] original = "before".getBytes(StandardCharsets.UTF_8);
        storage.write(key, new ByteArrayInputStream(original), "text/plain", original.length);
        var observed = storage.stat(key);
        try (var full = storage.readIfUnchanged(key, observed);
             var range = storage.readRangeIfUnchanged(key, observed, 1, 3)) {
            assertThat(full.readAllBytes()).isEqualTo(original);
            assertThat(range.readAllBytes()).isEqualTo("efo".getBytes(StandardCharsets.UTF_8));
        }
        byte[] replacement = "after!".getBytes(StandardCharsets.UTF_8);
        client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                .stream(new ByteArrayInputStream(replacement), replacement.length, -1).build());
        assertThatThrownBy(() -> storage.readIfUnchanged(key, observed))
                .isInstanceOf(StorageConflictException.class);
        assertThatThrownBy(() -> storage.readRangeIfUnchanged(key, observed, 0, 2))
                .isInstanceOf(StorageConflictException.class);
    }

    @Test void deletingAnObservedVersionCannotRemoveANewerVersion() throws Exception {
        String key = "users/101/files/conditional-delete.txt";
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        var old = storage.write(key, new ByteArrayInputStream(original), "text/plain", original.length);
        assertThat(old.versionId()).isNotBlank();
        byte[] replacement = "new-data".getBytes(StandardCharsets.UTF_8);
        client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                .stream(new ByteArrayInputStream(replacement), replacement.length, -1).build());
        assertThatThrownBy(() -> storage.delete(key, storage.stat(key).sha256()))
                .isInstanceOf(StorageConflictException.class);
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key)
                .versionId(old.versionId()).build());
        try (var input = storage.read(key)) { assertThat(input.readAllBytes()).isEqualTo(replacement); }
    }

    @Test void inventoryFindsHistoryEvenWhenADeleteMarkerHidesTheCurrentObject() throws Exception {
        String multiple = "users/101/files/multiple.txt";
        String hidden = "users/101/files/hidden.txt";
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        storage.write(multiple, new ByteArrayInputStream(first), "text/plain", first.length);
        storage.write(hidden, new ByteArrayInputStream(first), "text/plain", first.length);
        byte[] second = "second".getBytes(StandardCharsets.UTF_8);
        client.putObject(PutObjectArgs.builder().bucket(bucket).object(multiple)
                .stream(new ByteArrayInputStream(second), second.length, -1).build());
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(hidden).build());
        assertThat(storage.exists(hidden)).isFalse();
        assertThat(storage.listOwnedKeys()).contains(multiple).doesNotContain(hidden);
        assertThat(storage.listOwnedVersionIssues()).contains(
                new com.coffer.file.storage.FileStoragePort.VersionIssue(multiple, "MULTIPLE_VERSIONS"),
                new com.coffer.file.storage.FileStoragePort.VersionIssue(hidden, "DELETE_MARKER"));
    }

    @Test void hiddenHistoryCannotBeReusedAsAnEmptyKey() throws Exception {
        String hidden = "users/101/files/hidden-reuse.txt";
        String neighbor = hidden + ".neighbor";
        byte[] original = "first".getBytes(StandardCharsets.UTF_8);
        storage.write(hidden, new ByteArrayInputStream(original), "text/plain", original.length);
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(hidden).build());
        assertThat(storage.exists(hidden)).isFalse();

        byte[] replacement = "second".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> storage.write(hidden, new ByteArrayInputStream(replacement),
                "text/plain", replacement.length)).isInstanceOf(StorageConflictException.class);
        int versions = 0;
        for (var listed : client.listObjects(ListObjectsArgs.builder().bucket(bucket)
                .prefix(hidden).recursive(true).includeVersions(true).build())) {
            if (hidden.equals(listed.get().objectName())) versions++;
        }
        assertThat(versions).isEqualTo(2); // Original object and marker; no new version was created.
        assertThat(storage.write(neighbor, new ByteArrayInputStream(replacement),
                "text/plain", replacement.length).sha256()).isNotBlank();
    }

    @Test void verifiedSourceOpenRetainsOwnerIdentityAcrossItsWorkerThread() throws Exception {
        String key = "users/101/files/verified-source.txt";
        byte[] bytes = "verified source".getBytes(StandardCharsets.UTF_8);
        var stored = storage.write(key, new ByteArrayInputStream(bytes), "text/plain", bytes.length);
        var file = com.coffer.file.domain.FileMetadata.builder().storagePath(key)
                .fileSize((long) bytes.length).contentSha256(stored.sha256()).build();

        try (var input = com.coffer.file.application.VerifiedFileSource.open(storage, file)) {
            assertThat(input.readAllBytes()).isEqualTo(bytes);
        }
    }
}
