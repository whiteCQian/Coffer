package com.coffer.service;

import com.coffer.config.MinioConfig;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.InsufficientDataException;
import io.minio.errors.InternalException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.ServerException;
import io.minio.errors.XmlParserException;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.UUID;

/**
 * MinIO 对象存储服务。
 *
 * <p>负责文件上传与读取。上传支持两种方式：指定 objectName（经 {@code PathGenerator}
 * 生成，供上传接口落库 storagePath）或自动生成（{@code files/{uuid}/unnamed}）。
 */
@Slf4j
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class MinioStorageService {

    private final MinioClient minioClient;
    private final MinioConfig.MinioProperties minioProperties;

    /**
     * 上传文件到 MinIO（自动生成对象名）。
     *
     * <p>objectName 自动生成（{@code files/{uuid}/unnamed}）。需要可控对象名时请调用
     * {@link #uploadFile(String, String, InputStream, String, long)} 显式指定 objectName。
     *
     * @param bucketName   存储桶名称，为空时使用配置中的默认桶
     * @param inputStream  文件输入流
     * @param contentType  MIME 类型
     * @param size         文件大小（字节）
     * @return 上传响应（含对象版本信息）
     */
    public ObjectWriteResponse uploadFile(String bucketName,
                                          InputStream inputStream, String contentType, long size) {
        String objectName = "users/" + com.coffer.auth.service.TenantContext.requireOwnerId() + "/files/" + UUID.randomUUID().toString() + "/unnamed";
        return uploadFile(bucketName, objectName, inputStream, contentType, size);
    }

    /**
     * 上传文件到 MinIO（指定对象名）。
     *
     * <p>上传到调用方给定的 {@code objectName}（即存储路径），由调用方保证路径唯一
     * （如经 {@code PathGenerator} 生成），落库的 storagePath 与实际对象名一致。
     *
     * @param bucketName   存储桶名称，为空时使用配置中的默认桶
     * @param objectName   对象名称（存储路径），不允许为空
     * @param inputStream  文件输入流
     * @param contentType  MIME 类型
     * @param size         文件大小（字节）
     * @return 上传响应（含对象版本信息）
     * @throws IllegalArgumentException objectName 为空或空白
     */
    public ObjectWriteResponse uploadFile(String bucketName, String objectName,
                                          InputStream inputStream, String contentType, long size) {
        if (objectName == null || objectName.isBlank()) {
            throw new IllegalArgumentException("objectName 不能为空");
        }
        requireOwnedPath(objectName);
        bucketName = resolveBucketName(bucketName);
        try {
            ObjectWriteResponse response = minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .stream(inputStream, size, -1)
                    .contentType(contentType)
                    .build());
            log.info("MinIO 文件上传成功，大小={}B", size);
            return response;
        } catch (ErrorResponseException | InsufficientDataException | InternalException
                 | InvalidKeyException | InvalidResponseException | IOException
                 | NoSuchAlgorithmException | ServerException | XmlParserException e) {
            log.error("MinIO 文件上传失败，异常类型={}", e.getClass().getSimpleName());
            throw new RuntimeException("MinIO 文件上传失败", e);
        }
    }

    /**
     * 获取对象存储中的文件输入流。
     *
     * @param bucketName 存储桶名称，为空时使用配置中的默认桶
     * @param objectName 对象名称（存储路径）
     * @return 文件输入流，调用方负责关闭
     */
    public InputStream getFileStream(String bucketName, String objectName) {
        requireOwnedPath(objectName);
        bucketName = resolveBucketName(bucketName);
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .build());
        } catch (ErrorResponseException | InsufficientDataException | InternalException
                 | InvalidKeyException | InvalidResponseException | IOException
                 | NoSuchAlgorithmException | ServerException | XmlParserException e) {
            log.error("MinIO 文件获取失败，异常类型={}", e.getClass().getSimpleName());
            throw new RuntimeException("MinIO 文件获取失败", e);
        }
    }

    /**
     * Read the stable object metadata needed by governance preview snapshots.
     *
     * @param bucketName bucket name, blank means the configured default bucket
     * @param objectName object key
     * @return ETag and object size
     */
    public ObjectSnapshot statFile(String bucketName, String objectName) {
        if (objectName == null || objectName.isBlank()) {
            throw new IllegalArgumentException("objectName 不能为空");
        }
        requireOwnedPath(objectName);
        bucketName = resolveBucketName(bucketName);
        try {
            StatObjectResponse response = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .build());
            return new ObjectSnapshot(response.etag(), response.size());
        } catch (ErrorResponseException | InsufficientDataException | InternalException
                 | InvalidKeyException | InvalidResponseException | IOException
                 | NoSuchAlgorithmException | ServerException | XmlParserException e) {
            log.error("MinIO 对象元数据读取失败，异常类型={}", e.getClass().getSimpleName());
            throw new RuntimeException("MinIO 对象元数据读取失败", e);
        }
    }

    /**
     * Check whether an object already exists without treating a normal 404 as a service failure.
     */
    public boolean objectExists(String bucketName, String objectName) {
        try {
            statFile(bucketName, objectName);
            return true;
        } catch (RuntimeException e) {
            Throwable cause = e;
            while (cause != null) {
                if (cause instanceof ErrorResponseException error
                        && isMissingObject(error)) {
                    return false;
                }
                cause = cause.getCause();
            }
            throw e;
        }
    }

    private boolean isMissingObject(ErrorResponseException error) {
        String code = error.errorResponse() == null ? null : error.errorResponse().code();
        return "NoSuchKey".equals(code) || "NoSuchObject".equals(code)
                || "NoSuchBucket".equals(code) || "NotFound".equals(code);
    }

    /** Stable object metadata used by preview optimistic checks. */
    public record ObjectSnapshot(String etag, long size) {
    }

    /**
     * 生成对象临时预览 URL（默认有效期 7 天）。
     *
     * @param bucketName 存储桶名称，为空时使用配置中的默认桶
     * @param objectName 对象名称（存储路径），不允许为空
     * @param duration   有效期，为空时默认 7 天
     * @return 临时预览 URL
     * @throws IllegalArgumentException objectName 为空或空白
     */
    public String generatePresignedUrl(String bucketName, String objectName, Duration duration) {
        if (objectName == null || objectName.isBlank()) {
            throw new IllegalArgumentException("objectName 不能为空");
        }
        requireOwnedPath(objectName);
        bucketName = resolveBucketName(bucketName);
        Duration effectiveDuration = (duration == null) ? Duration.ofDays(7) : duration;
        int expirySeconds = Math.toIntExact(effectiveDuration.getSeconds());
        try {
            String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucketName)
                    .object(objectName)
                    .expiry(expirySeconds)
                    .build());
            log.info("生成 MinIO 临时预览 URL，有效期={}s", expirySeconds);
            return url;
        } catch (ErrorResponseException | InsufficientDataException | InternalException
                 | InvalidKeyException | InvalidResponseException | IOException
                 | NoSuchAlgorithmException | ServerException | XmlParserException e) {
            log.error("生成 MinIO 临时预览 URL 失败，异常类型={}", e.getClass().getSimpleName());
            throw new RuntimeException("生成 MinIO 临时预览 URL 失败", e);
        }
    }

    /**
     * 删除对象存储中的文件。
     *
     * @param bucketName 存储桶名称，为空时使用配置中的默认桶
     * @param objectName 对象名称（存储路径），不允许为空
     * @throws IllegalArgumentException objectName 为空或空白
     */
    public void deleteFile(String bucketName, String objectName) {
        if (objectName == null || objectName.isBlank()) {
            throw new IllegalArgumentException("objectName 不能为空");
        }
        requireOwnedPath(objectName);
        bucketName = resolveBucketName(bucketName);
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .build());
            log.info("MinIO 文件删除成功");
        } catch (ErrorResponseException | InsufficientDataException | InternalException
                 | InvalidKeyException | InvalidResponseException | IOException
                 | NoSuchAlgorithmException | ServerException | XmlParserException e) {
            log.error("MinIO 文件删除失败，异常类型={}", e.getClass().getSimpleName());
            throw new RuntimeException("MinIO 文件删除失败", e);
        }
    }

    /**
     * 复制对象（服务端拷贝，不经应用内存），供持久化治理归档操作使用。
     *
     * <p>MinIO 无原生 rename，移动 = 先复制到新路径、再删除旧路径；步骤由治理操作台账协调。
     * 目标与源相同时幂等直接返回。
     *
     * @param sourceObject 源对象名称（存储路径）
     * @param targetObject 目标对象名称（存储路径）
     * @throws IllegalArgumentException 源或目标对象名为空或空白
     */
    public void copyObject(String sourceObject, String targetObject) {
        if (sourceObject == null || sourceObject.isBlank()) {
            throw new IllegalArgumentException("源对象名不能为空");
        }
        if (targetObject == null || targetObject.isBlank()) {
            throw new IllegalArgumentException("目标对象名不能为空");
        }
        requireOwnedPath(sourceObject);
        requireOwnedPath(targetObject);
        String bucketName = resolveBucketName(null);
        if (sourceObject.equals(targetObject)) {
            log.info("MinIO 复制目标与源相同，幂等跳过");
            return;
        }
        try {
            minioClient.copyObject(CopyObjectArgs.builder()
                    .bucket(bucketName)
                    .object(targetObject)
                    .source(CopySource.builder()
                            .bucket(bucketName)
                            .object(sourceObject)
                            .build())
                    .build());
            log.info("MinIO 对象复制成功");
        } catch (ErrorResponseException | InsufficientDataException | InternalException
                 | InvalidKeyException | InvalidResponseException | IOException
                 | NoSuchAlgorithmException | ServerException | XmlParserException e) {
            log.error("MinIO 对象复制失败，类型={}", e.getClass().getSimpleName());
            throw new RuntimeException("MinIO 对象复制失败", e);
        }
    }

    /**
     * 解析实际存储桶：bucketName 为空时回退到配置的默认桶。
     *
     * @param bucketName 传入的存储桶名
     * @return 实际使用的存储桶名
     */
    public static void requireOwnedPath(String path) {
        String prefix = "users/" + com.coffer.auth.service.TenantContext.requireOwnerId() + "/";
        if (path == null || !path.startsWith(prefix) || path.length() == prefix.length()
                || path.contains("\\") || path.chars().anyMatch(c -> c < 32)
                || java.util.Arrays.stream(path.split("/", -1)).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."))) {
            throw new com.coffer.auth.service.ResourceNotFoundException();
        }
    }

    private String resolveBucketName(String bucketName) {
        if (bucketName == null || bucketName.isBlank()) {
            return minioProperties.getBucketName();
        }
        if (!bucketName.equals(minioProperties.getBucketName())) {
            throw new com.coffer.auth.service.ResourceNotFoundException();
        }
        return bucketName;
    }
}
