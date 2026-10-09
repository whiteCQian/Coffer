package com.coffer.file.application;

import com.coffer.file.api.dto.FileUploadRequest;
import com.coffer.file.api.dto.FileUploadResponse;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.application.event.FileUploadedEvent;
import com.coffer.file.domain.service.FileTypeResolver;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.file.storage.StorageConflictException;
import com.coffer.file.api.support.FilenameEncodingFixer;
import com.coffer.file.infrastructure.storage.PathGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Application use case for uploading a file and registering its processing task. */
@Slf4j
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class FileUploadApplicationService {

    private final FileStoragePort storage;
    private final FileTypeResolver fileTypeResolver;
    private final PathGenerator pathGenerator;
    private final UploadPipelineService uploadPipelineService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final FileWriteIntentService writeIntents;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.coffer.inbox.infrastructure.persistence.InboxImportRecordRepository inboxRecords;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.coffer.file.infrastructure.persistence.FileMetadataRepository fileMetadataRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.coffer.inbox.application.InboxDirectoryResolver inboxDirectory;

    /** Uploads the object, registers DB metadata, and publishes a post-commit event. */
    @Transactional
    public FileUploadResponse upload(FileUploadRequest request) {
        return upload(request, UUID.randomUUID().toString());
    }

    /** A caller retries the same multipart body with the same UUID after a lost response. */
    @Transactional
    public FileUploadResponse upload(FileUploadRequest request, String idempotencyKey) {
        MultipartFile file = request.getFile();
        String fileName = FilenameEncodingFixer.fix(file.getOriginalFilename());
        if (file.getSize() < 0 || file.getSize() > com.coffer.file.application.parse.BoundedDocumentParser.MAX_BYTES)
            throw new IllegalArgumentException("文件超过 32MB 处理上限");
        UUID requestId;
        try { requestId = UUID.fromString(idempotencyKey); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("幂等键必须为 UUID"); }
        String operationId = UUID.nameUUIDFromBytes(("upload:"
                + com.coffer.auth.service.TenantContext.requireOwnerId() + ":" + requestId)
                .getBytes(StandardCharsets.UTF_8)).toString();
        String requestSha256 = sha256(file);
        var prior = writeIntents.existing(operationId);
        if (prior.isPresent()) {
            var intent = prior.orElseThrow();
            if (!"UPLOAD".equals(intent.getKind()) || !Objects.equals(fileName, intent.getFileName())
                    || file.getSize() != intent.getDeclaredSize())
                throw new StorageConflictException("幂等键已用于其他上传");
            if (intent.getStatus() == com.coffer.file.domain.FileWriteIntentStatus.REGISTERED
                    && Objects.equals(requestSha256, intent.getContentSha256())
                    && fileMetadataRepository != null) {
                FileMetadata registered = fileMetadataRepository.findByTaskId(intent.getTaskId())
                        .orElseThrow(() -> new StorageConflictException("上传台账与文件不一致"));
                return FileUploadResponse.builder().taskId(intent.getTaskId()).fileName(intent.getFileName())
                        .fileSize(intent.getDeclaredSize()).status(registered.getStatus().name())
                        .uploadTime(registered.getUploadTime()).build();
            }
            throw new StorageConflictException("上传已登记，请在文件操作台账查看状态");
        }

        try (InputStream inputStream = file.getInputStream()) {
            return uploadStream("UPLOAD", fileName, file.getContentType(), file.getSize(), inputStream,
                    null, operationId, requestSha256);
        } catch (IOException e) {
            log.error("读取上传文件失败，类型={}", e.getClass().getSimpleName());
            throw new RuntimeException("文件上传失败", e);
        }
    }

    /**
     * Imports one stable inbox file through the same object-storage and analysis-task
     * registration path as a multipart upload.
     *
     * <p>The expected size and modification timestamp are checked both before and
     * after the copy. If a producer is still changing the file, the transaction is
     * rolled back and the temporary object is compensated.</p>
     */
    @Transactional
    public FileUploadResponse importInboxFile(Path sourcePath,
                                              long expectedSize,
                                              long expectedModifiedMillis) {
        return importInboxFileInternal(sourcePath, expectedSize, expectedModifiedMillis, null);
    }

    /** The inbox ledger and newly registered file commit in the same transaction. */
    @Transactional
    public FileUploadResponse importInboxFile(Path sourcePath,
                                              long expectedSize,
                                              long expectedModifiedMillis,
                                              Long inboxRecordId) {
        return importInboxFileInternal(sourcePath, expectedSize, expectedModifiedMillis, inboxRecordId);
    }

    private FileUploadResponse importInboxFileInternal(Path sourcePath,
                                                       long expectedSize,
                                                       long expectedModifiedMillis,
                                                       Long inboxRecordId) {
        if (sourcePath == null || sourcePath.getFileName() == null) {
            throw new IllegalArgumentException("收件箱文件路径不能为空");
        }
        String fileName = FilenameEncodingFixer.fix(sourcePath.getFileName().toString());
        if (inboxDirectory != null) sourcePath = inboxDirectory.requireSource(sourcePath);
        com.coffer.inbox.domain.InboxImportRecord preview = inboxRecordId == null ? null : inboxRecords.findById(inboxRecordId)
                .orElseThrow(com.coffer.auth.service.ResourceNotFoundException::new);
        if (preview != null && (!preview.getSourceFileName().equals(fileName) || !preview.getSourceSize().equals(expectedSize)
                || preview.getStatus() != com.coffer.inbox.domain.InboxImportStatus.IMPORTING))
            throw new StorageConflictException("收件箱确认快照不一致");
        try (LocalImportSource source = LocalImportSource.open(sourcePath, expectedSize, expectedModifiedMillis,
                preview == null ? null : preview.getSourceFileKey())) {
            String digest = source.sha256();
            if (preview != null && !java.util.Objects.equals(preview.getContentSha256(), digest))
                throw new StorageConflictException("收件箱正文已变化，请重新扫描预览");
            String contentType = Files.probeContentType(sourcePath);
            if (contentType == null || contentType.isBlank()) {
                contentType = "application/octet-stream";
            }
            {
                String finalContentType = contentType;
                FileUploadResponse response = uploadStream("IMPORT", fileName, finalContentType, expectedSize, source.stream(),
                        () -> { try { source.verify(); } catch (IOException error) { throw new UncheckedIOException("来源校验失败", error); } },
                        null, digest, preview == null ? null : preview.getTargetPath());
                if (inboxRecordId != null) {
                    if (inboxRecords == null) throw new IllegalStateException("收件箱台账不可用");
                    var record = inboxRecords.findById(inboxRecordId)
                            .orElseThrow(com.coffer.auth.service.ResourceNotFoundException::new);
                    if (fileMetadataRepository == null) throw new IllegalStateException("文件台账不可用");
                    FileMetadata registered = fileMetadataRepository.findByTaskId(response.getTaskId())
                            .orElseThrow(() -> new IllegalStateException("导入文件登记失败"));
                    if (record.getStatus() != com.coffer.inbox.domain.InboxImportStatus.IMPORTING
                            || !record.getSourceFileName().equals(fileName)
                            || !record.getSourceSize().equals(expectedSize)
                            || !record.getContentSha256().equals(registered.getContentSha256()))
                        throw new IllegalStateException("收件箱快照与已登记文件不一致");
                    record.setStatus(com.coffer.inbox.domain.InboxImportStatus.IMPORTED);
                    record.setTaskId(response.getTaskId());
                    record.setFileId(registered.getId());
                    record.setImportFinishedAt(java.time.LocalDateTime.now());
                    record.setNextAttemptAt(null);
                    record.setLastError(null);
                    record.setUpdatedAt(java.time.LocalDateTime.now());
                    inboxRecords.save(record);
                }
                return response;
            }
        } catch (IOException e) {
            log.warn("读取收件箱文件失败，类型={}", e.getClass().getSimpleName());
            throw new RuntimeException("收件箱文件读取失败，请检查占用和权限", e);
        }
    }

    /** Stores an input stream, registers the async task, and publishes the post-commit event. */
    private FileUploadResponse uploadStream(String kind, String fileName,
                                            String contentType,
                                            long fileSize,
                                            InputStream inputStream,
                                            Runnable afterUploadCheck,
                                            String requestedOperationId,
                                            String expectedSha256) {
        return uploadStream(kind, fileName, contentType, fileSize, inputStream, afterUploadCheck,
                requestedOperationId, expectedSha256, null);
    }

    private FileUploadResponse uploadStream(String kind, String fileName, String contentType, long fileSize,
                                            InputStream inputStream, Runnable afterUploadCheck, String requestedOperationId,
                                            String expectedSha256, String confirmedTarget) {
        if (fileSize < 0 || fileSize > com.coffer.file.application.parse.BoundedDocumentParser.MAX_BYTES) {
            throw new IllegalArgumentException("文件超过 32MB 处理上限");
        }
        String fileType = fileTypeResolver.resolve(fileName);
        String storagePath = confirmedTarget == null ? pathGenerator.generateStoragePath(fileName, fileType)
                : com.coffer.file.storage.StorageKey.requireOwned(confirmedTarget);
        String taskId = UUID.randomUUID().toString();
        String operationId = requestedOperationId == null ? UUID.randomUUID().toString() : requestedOperationId;
        writeIntents.begin(operationId, taskId, kind, storagePath, fileName, fileType,
                contentType, fileSize, com.coffer.model.runtime.ModelExecutionContext.currentId());
        try {
            FileStoragePort.StoredObject stored = "IMPORT".equals(kind)
                    ? storage.writeVerified(storagePath, inputStream, contentType, fileSize, expectedSha256)
                    : storage.write(storagePath, inputStream, contentType, fileSize);
            if (stored.size() != fileSize) throw new IllegalStateException("文件大小在写入期间发生变化");
            if (expectedSha256 != null && !expectedSha256.equals(stored.sha256()))
                throw new StorageConflictException("文件在上传期间发生变化");
            writeIntents.objectWritten(operationId, stored);
            if (afterUploadCheck != null) afterUploadCheck.run();

            FileMetadata metadata = uploadPipelineService.registerUploadTask(
                    taskId, fileName, fileType, storagePath, fileSize, stored.sha256());
            applicationEventPublisher.publishEvent(new FileUploadedEvent(taskId));
            TransactionSynchronization completion = new TransactionSynchronization() {
                @Override public void afterCommit() {
                    try { writeIntents.registered(operationId); }
                    catch (RuntimeException registrationFailure) {
                        log.error("上传事务已提交但意图尚未完成，稍后对账 operationId={} type={}",
                                operationId, registrationFailure.getClass().getSimpleName());
                    }
                }
            };
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(completion);
            } else {
                completion.afterCommit();
            }

            log.info("文件写入并登记成功 taskId={}, size={}B", taskId, fileSize);
            return FileUploadResponse.builder()
                    .taskId(taskId)
                    .fileName(fileName)
                    .fileSize(fileSize)
                    .status("PENDING")
                    .uploadTime(metadata.getUploadTime())
                    .build();
        } catch (RuntimeException e) {
            // The durable intent retains the object identity for restart reconciliation.
            // A failed outer transaction must not silently erase an object that may be recoverable.
            try { writeIntents.failed(operationId, e.getClass().getSimpleName()); }
            catch (RuntimeException trackingFailure) { e.addSuppressed(trackingFailure); }
            throw e;
        }
    }

    private String sha256(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            long length = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                length += count;
                if (length > com.coffer.file.application.parse.BoundedDocumentParser.MAX_BYTES)
                    throw new IllegalArgumentException("文件超过 32MB 处理上限");
                digest.update(buffer, 0, count);
            }
            if (length != file.getSize()) throw new IllegalArgumentException("文件在上传期间发生变化");
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException error) {
            throw new IllegalStateException("文件上传读取失败", error);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private void verifyStableSnapshot(Path sourcePath, long expectedSize, long expectedModifiedMillis)
            throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(sourcePath,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() != expectedSize
                || attributes.lastModifiedTime().toMillis() != expectedModifiedMillis) {
            throw new IllegalStateException("文件在导入期间发生变化: " + sourcePath);
        }
    }

    private void verifyStableSnapshotUnchecked(Path sourcePath, long expectedSize, long expectedModifiedMillis) {
        try {
            verifyStableSnapshot(sourcePath, expectedSize, expectedModifiedMillis);
        } catch (IOException e) {
            throw new UncheckedIOException("无法确认收件箱文件是否稳定: " + sourcePath, e);
        }
    }

}
