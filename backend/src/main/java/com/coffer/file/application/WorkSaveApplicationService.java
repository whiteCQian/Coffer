package com.coffer.file.application;

import com.coffer.file.domain.FileMetadata;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.infrastructure.storage.PathGenerator;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.file.storage.StorageConflictException;
import com.coffer.model.runtime.ModelExecutionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Saves edited bytes to a new immutable key, then conditionally changes the formal file. */
@Service @RequiredArgsConstructor @com.coffer.auth.service.OwnerOnly
public class WorkSaveApplicationService {
    private final FileMetadataRepository files;
    private final FileStoragePort storage;
    private final PathGenerator paths;
    private final WorkSaveIntentService intents;

    public record Result(String operationId, Long fileId, long revision, String taskId) { }

    public Result save(Long fileId, long expectedRevision, String expectedSha256,
                       String idempotencyKey, MultipartFile edited) {
        if (edited == null || edited.getSize() < 0 || edited.getSize() >
                com.coffer.file.application.parse.BoundedDocumentParser.MAX_BYTES)
            throw new IllegalArgumentException("工作副本超过 32MB 上限");
        var model = ModelExecutionContext.require();
        UUID clientKey;
        try { clientKey = UUID.fromString(idempotencyKey); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("幂等键必须为 UUID"); }
        String operationId = UUID.nameUUIDFromBytes((com.coffer.auth.service.TenantContext.requireOwnerId()
                + ":" + fileId + ":" + clientKey).getBytes(StandardCharsets.UTF_8)).toString();
        String requestSha256 = sha256(edited);
        var existing = intents.existing(operationId);
        if (existing.isPresent()) {
            var intent = existing.orElseThrow();
            if (!Objects.equals(intent.getFileId(), fileId)
                    || intent.getExpectedRevision() != expectedRevision
                    || !Objects.equals(intent.getBeforeSha256(), expectedSha256)
                    || intent.getTargetSize() != edited.getSize()
                    || !Objects.equals(intent.getRequestSha256(), requestSha256))
                throw new StorageConflictException("幂等键已用于另一份工作副本");
            if (intent.getStatus() == com.coffer.file.domain.WorkSaveStatus.COMMITTED)
                return new Result(operationId, fileId, expectedRevision + 1, intent.getTaskId());
            throw new StorageConflictException("工作副本保存已登记，请在操作台账查看状态");
        }
        FileMetadata formal = files.findById(fileId)
                .orElseThrow(com.coffer.auth.service.ResourceNotFoundException::new);
        String sourceName = edited.getOriginalFilename();
        if (formal.getFileType() == null || sourceName == null
                || !sourceName.toLowerCase(java.util.Locale.ROOT)
                    .endsWith("." + formal.getFileType().toLowerCase(java.util.Locale.ROOT)))
            throw new IllegalArgumentException("工作副本格式必须与正式文件一致");
        if (!Objects.equals(formal.getRevision(), expectedRevision)
                || expectedSha256 == null || !expectedSha256.equals(formal.getContentSha256()))
            throw new StorageConflictException("正式文件版本已变化");
        var before = storage.stat(formal.getStoragePath());
        if (!expectedSha256.equals(before.sha256()) || !Objects.equals(formal.getFileSize(), before.size()))
            throw new StorageConflictException("正式文件正文已变化");
        String taskId = UUID.randomUUID().toString();
        String targetKey = paths.generateStoragePath(formal.getFileName(), formal.getFileType());
        intents.prepare(operationId, formal, expectedRevision, expectedSha256, targetKey,
                edited.getSize(), requestSha256, taskId, model.id(), model.mode());
        try (InputStream input = edited.getInputStream()) {
            var object = storage.write(targetKey, input, edited.getContentType(), edited.getSize());
            intents.objectWritten(operationId, object);
            if (!intents.commit(operationId)) throw new StorageConflictException("正式文件已变化，工作副本保留待恢复");
            return new Result(operationId, fileId, expectedRevision + 1, taskId);
        } catch (IOException error) {
            IllegalStateException failure = new IllegalStateException("工作副本读取失败", error);
            intents.failure(operationId, failure);
            throw failure;
        } catch (RuntimeException failure) {
            try { intents.failure(operationId, failure); }
            catch (RuntimeException trackingFailure) { failure.addSuppressed(trackingFailure); }
            throw failure;
        }
    }

    private String sha256(MultipartFile source) {
        try (InputStream input = source.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            long read = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                read += count;
                if (read > com.coffer.file.application.parse.BoundedDocumentParser.MAX_BYTES)
                    throw new IllegalArgumentException("工作副本超过 32MB 上限");
                digest.update(buffer, 0, count);
            }
            if (read != source.getSize()) throw new IllegalArgumentException("工作副本长度发生变化");
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException error) {
            throw new IllegalStateException("工作副本读取失败", error);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }
}
