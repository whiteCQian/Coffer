package com.coffer.service;

import com.coffer.config.MinioConfig;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.file.storage.StorageConflictException;
import com.coffer.file.storage.StorageKey;
import com.coffer.file.storage.StorageObjectNotFoundException;
import io.minio.GetObjectArgs;
import io.minio.GetBucketVersioningArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Server-only implementation of the owner-scoped storage port. */
@Slf4j
@com.coffer.auth.service.OwnerOnly
@Service
@Profile("!desktop")
@RequiredArgsConstructor
public class MinioStorageService implements FileStoragePort {

    private final MinioClient minioClient;
    private final MinioConfig.MinioProperties minioProperties;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.coffer.web.WebLimits webLimits;

    @Override
    public java.util.List<String> listOwnedKeys() {
        String prefix = "users/" + com.coffer.auth.service.TenantContext.requireOwnerId() + "/";
        java.util.List<String> keys = new java.util.ArrayList<>();
        try {
            for (var listed : minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(resolveBucketName(null)).prefix(prefix).recursive(true).build())) {
                String key = listed.get().objectName();
                StorageKey.requireOwned(key);
                keys.add(key);
            }
            return keys;
        } catch (Exception error) {
            throw new IllegalStateException("MinIO 对象清单读取失败", error);
        }
    }

    @Override
    public java.util.List<VersionIssue> listOwnedVersionIssues() {
        String prefix = "users/" + com.coffer.auth.service.TenantContext.requireOwnerId() + "/";
        java.util.Map<String, VersionState> versions = new java.util.LinkedHashMap<>();
        try {
            for (var listed : minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(resolveBucketName(null)).prefix(prefix).recursive(true)
                    .includeVersions(true).build())) {
                var item = listed.get();
                String key = item.objectName();
                StorageKey.requireOwned(key);
                VersionState state = versions.computeIfAbsent(key, ignored -> new VersionState());
                state.count++;
                state.hasDeleteMarker |= item.isDeleteMarker();
                state.hasUnversionedObject |= item.versionId() == null
                        || item.versionId().isBlank() || "null".equals(item.versionId());
            }
            java.util.List<VersionIssue> issues = new java.util.ArrayList<>();
            versions.forEach((key, state) -> {
                if (state.hasDeleteMarker) issues.add(new VersionIssue(key, "DELETE_MARKER"));
                else if (state.count > 1) issues.add(new VersionIssue(key, "MULTIPLE_VERSIONS"));
                else if (state.hasUnversionedObject) issues.add(new VersionIssue(key, "UNVERSIONED_OBJECT"));
            });
            return issues;
        } catch (Exception error) {
            throw new IllegalStateException("MinIO 历史版本清单读取失败", error);
        }
    }

    private static final class VersionState {
        int count;
        boolean hasDeleteMarker;
        boolean hasUnversionedObject;
    }

    @Override
    public StoredObject write(String key, InputStream source, String contentType, long size) {
        StorageKey.requireOwned(key);
        if (source == null || size < 0) throw new IllegalArgumentException("无效的文件流或长度");
        if (webLimits != null) webLimits.reserve(key, size);
        requireVersionedBucket();
        Path staged = null;
        try {
            staged = Files.createTempFile("coffer-object-", ".stage");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long copied = 0;
            try (OutputStream target = Files.newOutputStream(staged)) {
                byte[] bytes = new byte[64 * 1024];
                int count;
                while ((count = source.read(bytes)) != -1) {
                    copied += count;
                    if (copied > size) throw new IllegalArgumentException("文件长度与声明值不一致");
                    digest.update(bytes, 0, count);
                    target.write(bytes, 0, count);
                }
            }
            if (copied != size) throw new IllegalArgumentException("文件长度与声明值不一致");
            String sha256 = HexFormat.of().formatHex(digest.digest());
            requireNoVersions(key);
            try (InputStream stagedInput = Files.newInputStream(staged)) {
                ObjectWriteResponse response = minioClient.putObject(PutObjectArgs.builder()
                        .bucket(resolveBucketName(null)).object(key)
                        .stream(stagedInput, size, -1)
                        .contentType(contentType == null ? "application/octet-stream" : contentType)
                        .headers(Map.of("If-None-Match", "*"))
                        .userMetadata(Map.of("sha256", sha256)).build());
                if (response.versionId() == null || response.versionId().isBlank())
                    throw new StorageConflictException("MinIO 写入未返回对象版本，需人工核对");
                requireOnlyVersion(key, response.versionId());
                if (webLimits != null) webLimits.stored(key);
                return new StoredObject(key, size, sha256, response.etag(), response.versionId());
            } catch (ErrorResponseException e) {
                if (isWriteConflict(e)) throw new StorageConflictException(key);
                throw new IllegalStateException("MinIO 对象写入失败", e);
            } catch (StorageConflictException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("MinIO 对象写入失败", e);
            }
        } catch (IOException e) {
            throw new IllegalStateException("暂存上传文件失败", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        } finally {
            if (staged != null) {
                try { Files.deleteIfExists(staged); }
                catch (IOException e) { log.warn("上传暂存文件清理失败 type={}", e.getClass().getSimpleName()); }
            }
        }
    }

    @Override
    public InputStream read(String key) {
        StorageKey.requireOwned(key);
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(resolveBucketName(null)).object(key).build());
        } catch (ErrorResponseException e) {
            if (isMissingObject(e)) throw new StorageObjectNotFoundException(key);
            throw new IllegalStateException("MinIO 对象读取失败", e);
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 对象读取失败", e);
        }
    }

    @Override
    public InputStream readRange(String key, long offset, long length) {
        StorageKey.requireOwned(key);
        if (offset < 0 || length <= 0) throw new IllegalArgumentException("无效的读取范围");
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(resolveBucketName(null)).object(key)
                    .offset(offset).length(length).build());
        } catch (ErrorResponseException e) {
            if (isMissingObject(e)) throw new StorageObjectNotFoundException(key);
            throw new IllegalStateException("MinIO 范围读取失败", e);
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 范围读取失败", e);
        }
    }

    @Override
    public InputStream readIfUnchanged(String key, StoredObject expected) {
        return openIfUnchanged(key, expected, null, null);
    }

    @Override
    public InputStream readRangeIfUnchanged(String key, StoredObject expected, long offset, long length) {
        if (offset < 0 || length <= 0 || expected == null || offset > expected.size()
                || length > expected.size() - offset) throw new IllegalArgumentException("无效的读取范围");
        return openIfUnchanged(key, expected, offset, length);
    }

    private InputStream openIfUnchanged(String key, StoredObject expected, Long offset, Long length) {
        StorageKey.requireOwned(key);
        if (expected == null || !key.equals(expected.key()) || expected.etag() == null
                || expected.etag().isBlank() || expected.sha256() == null
                || !expected.sha256().matches("[0-9a-f]{64}") || expected.size() < 0)
            throw new IllegalArgumentException("已校验对象身份无效");
        try {
            var request = GetObjectArgs.builder().bucket(resolveBucketName(null)).object(key)
                    .matchETag(expected.etag());
            if (offset != null) request.offset(offset).length(length);
            return minioClient.getObject(request.build());
        } catch (ErrorResponseException e) {
            if (isMissingObject(e)) throw new StorageObjectNotFoundException(key);
            if (isWriteConflict(e)) throw new StorageConflictException("对象在校验后已变化");
            throw new IllegalStateException("MinIO 对象读取失败", e);
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 对象读取失败", e);
        }
    }

    @Override
    public StoredObject stat(String key) {
        StorageKey.requireOwned(key);
        try {
            StatObjectResponse response = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(resolveBucketName(null)).object(key).build());
            // User metadata is only a hint. A stale or copied sha256 header must
            // never stand in for the bytes used by authorization and deletion.
            String recordedSha256 = response.userMetadata() == null
                    ? null : response.userMetadata().get("sha256");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long actualSize;
            if (response.etag() == null || response.etag().isBlank())
                throw new com.coffer.file.storage.StorageConflictException("对象缺少版本标识");
            var verifiedRead = GetObjectArgs.builder().bucket(resolveBucketName(null))
                    .object(key).matchETag(response.etag());
            if (response.versionId() != null && !response.versionId().isBlank())
                verifiedRead.versionId(response.versionId());
            try (InputStream input = minioClient.getObject(verifiedRead.build());
                 DigestInputStream hashed = new DigestInputStream(input, digest)) {
                actualSize = hashed.transferTo(OutputStream.nullOutputStream());
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            if (actualSize != response.size()
                    || (recordedSha256 != null && recordedSha256.matches("[0-9a-f]{64}")
                        && !recordedSha256.equals(sha256)))
                throw new com.coffer.file.storage.StorageConflictException("对象内容与存储元数据不一致");
            return new StoredObject(key, response.size(), sha256, response.etag(), response.versionId());
        } catch (com.coffer.file.storage.StorageConflictException e) {
            throw e;
        } catch (ErrorResponseException e) {
            if (isMissingObject(e)) throw new StorageObjectNotFoundException(key);
            if (isWriteConflict(e)) throw new StorageConflictException("对象在校验时已变化");
            throw new IllegalStateException("MinIO 对象状态读取失败", e);
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 对象状态读取失败", e);
        }
    }

    @Override
    public boolean exists(String key) {
        try { stat(key); return true; }
        catch (StorageObjectNotFoundException e) { return false; }
    }

    @Override
    public StoredObject copy(String source, String target, String expectedSourceSha256) {
        StorageKey.requireOwned(source);
        StorageKey.requireOwned(target);
        if (source.equals(target)) throw new StorageConflictException(target);
        StoredObject original = stat(source);
        if (expectedSourceSha256 == null || !expectedSourceSha256.equals(original.sha256())) {
            throw new StorageConflictException(source);
        }
        try (InputStream input = read(source)) {
            StoredObject copied = write(target, input, "application/octet-stream", original.size());
            if (!copied.sha256().equals(original.sha256())) {
                delete(target, copied.sha256());
                throw new IllegalStateException("复制后文件摘要不一致");
            }
            return copied;
        } catch (IOException e) {
            throw new IllegalStateException("MinIO 对象复制失败", e);
        }
    }

    @Override
    public StoredObject move(String source, String target, String expectedSourceSha256) {
        StoredObject result = copy(source, target, expectedSourceSha256);
        delete(source, expectedSourceSha256);
        return result;
    }

    @Override
    public void delete(String key, String expectedSha256) {
        StorageKey.requireOwned(key);
        requireVersionedBucket();
        StoredObject current;
        try { current = stat(key); }
        catch (StorageObjectNotFoundException missing) {
            if (webLimits != null) { requireNoVersions(key); webLimits.deleted(key); }
            throw missing;
        }
        if (expectedSha256 == null || !expectedSha256.equals(current.sha256())) {
            throw new StorageConflictException(key);
        }
        if (current.versionId() == null || current.versionId().isBlank()
                || "null".equals(current.versionId()))
            throw new StorageConflictException("对象缺少可安全删除的版本身份，需人工核对");
        try {
            requireOnlyVersion(key, current.versionId());
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(resolveBucketName(null)).object(key)
                    .versionId(current.versionId()).build());
            requireNoVersions(key);
            if (webLimits != null) webLimits.deleted(key);
        } catch (StorageConflictException e) {
            throw e;
        } catch (ErrorResponseException e) {
            if (isWriteConflict(e)) throw new StorageConflictException(key);
            throw new IllegalStateException("MinIO 对象删除失败", e);
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 对象删除失败", e);
        }
    }

    private void requireNoVersions(String key) {
        checkVersions(key, null);
    }

    private void requireOnlyVersion(String key, String expectedVersionId) {
        checkVersions(key, expectedVersionId);
    }

    /** A delete marker makes stat/HEAD look empty while old bytes remain. */
    private void checkVersions(String key, String expectedVersionId) {
        try {
            int count = 0;
            for (var listed : minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(resolveBucketName(null)).prefix(key).recursive(true)
                    .includeVersions(true).build())) {
                var version = listed.get();
                if (!key.equals(version.objectName())) continue;
                count++;
                if (expectedVersionId == null || count > 1 || version.isDeleteMarker()
                        || !expectedVersionId.equals(version.versionId()))
                    throw new StorageConflictException("对象存在其他版本或删除标记，需人工核对");
            }
            if (expectedVersionId != null && count != 1)
                throw new StorageConflictException("对象版本清单不一致，需人工核对");
        } catch (StorageConflictException conflict) {
            throw conflict;
        } catch (Exception failure) {
            throw new IllegalStateException("MinIO 对象版本清单读取失败", failure);
        }
    }

    private void requireVersionedBucket() {
        try {
            var config = minioClient.getBucketVersioning(GetBucketVersioningArgs.builder()
                    .bucket(resolveBucketName(null)).build());
            if (config == null || config.status() != io.minio.messages.VersioningConfiguration.Status.ENABLED)
                throw new StorageConflictException("MinIO bucket 未启用版本控制，拒绝写入或物理删除");
        } catch (StorageConflictException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("MinIO bucket 版本状态检查失败", e);
        }
    }

    private boolean isWriteConflict(ErrorResponseException error) {
        String code = error.errorResponse() == null ? null : error.errorResponse().code();
        return "PreconditionFailed".equals(code) || "ConditionalRequestConflict".equals(code)
                || error.response().code() == 409 || error.response().code() == 412;
    }

    private boolean isMissingObject(ErrorResponseException error) {
        String code = error.errorResponse() == null ? null : error.errorResponse().code();
        return "NoSuchKey".equals(code) || "NoSuchObject".equals(code)
                || "NoSuchBucket".equals(code) || "NotFound".equals(code);
    }

    private String resolveBucketName(String bucketName) {
        if (bucketName == null || bucketName.isBlank()) return minioProperties.getBucketName();
        if (!bucketName.equals(minioProperties.getBucketName()))
            throw new com.coffer.auth.service.ResourceNotFoundException();
        return bucketName;
    }
}
