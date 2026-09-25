package com.coffer.file.application;

import com.coffer.file.api.dto.FileUploadRequest;
import com.coffer.file.api.dto.FileUploadResponse;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.application.event.FileUploadedEvent;
import com.coffer.file.domain.service.FileTypeResolver;
import com.coffer.service.MinioStorageService;
import com.coffer.file.api.support.FilenameEncodingFixer;
import com.coffer.file.infrastructure.storage.PathGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Application use case for uploading a file and registering its processing task. */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileUploadApplicationService {

    private final MinioStorageService minioStorageService;
    private final FileTypeResolver fileTypeResolver;
    private final PathGenerator pathGenerator;
    private final UploadPipelineService uploadPipelineService;
    private final ApplicationEventPublisher applicationEventPublisher;

    /** Uploads the object, registers DB metadata, and publishes a post-commit event. */
    @Transactional
    public FileUploadResponse upload(FileUploadRequest request) {
        MultipartFile file = request.getFile();
        String fileName = FilenameEncodingFixer.fix(file.getOriginalFilename());

        try (InputStream inputStream = file.getInputStream()) {
            return uploadStream(fileName, file.getContentType(), file.getSize(), inputStream, null);
        } catch (IOException e) {
            log.error("读取上传文件失败 fileName={}: {}", fileName, e.getMessage(), e);
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
        if (sourcePath == null || sourcePath.getFileName() == null) {
            throw new IllegalArgumentException("收件箱文件路径不能为空");
        }
        String fileName = FilenameEncodingFixer.fix(sourcePath.getFileName().toString());
        try {
            verifyStableSnapshot(sourcePath, expectedSize, expectedModifiedMillis);
            String contentType = Files.probeContentType(sourcePath);
            if (contentType == null || contentType.isBlank()) {
                contentType = "application/octet-stream";
            }
            try (InputStream inputStream = Files.newInputStream(sourcePath)) {
                String finalContentType = contentType;
                return uploadStream(fileName, finalContentType, expectedSize, inputStream,
                        () -> verifyStableSnapshotUnchecked(sourcePath, expectedSize, expectedModifiedMillis));
            }
        } catch (IOException e) {
            log.warn("读取收件箱文件失败 fileName={}, path={}: {}",
                    fileName, sourcePath, e.getMessage());
            throw new RuntimeException("收件箱文件读取失败: " + fileName, e);
        }
    }

    /** Stores an input stream, registers the async task, and publishes the post-commit event. */
    private FileUploadResponse uploadStream(String fileName,
                                            String contentType,
                                            long fileSize,
                                            InputStream inputStream,
                                            Runnable afterUploadCheck) {
        String fileType = fileTypeResolver.resolve(fileName);
        String storagePath = pathGenerator.generateStoragePath(fileName, fileType);
        boolean objectUploaded = false;
        try {
            minioStorageService.uploadFile(null, storagePath, inputStream, contentType, fileSize);
            objectUploaded = true;

            String taskId = UUID.randomUUID().toString();
            FileMetadata metadata = uploadPipelineService.registerUploadTask(
                    taskId, fileName, fileType, storagePath, fileSize);
            applicationEventPublisher.publishEvent(new FileUploadedEvent(taskId));
            if (afterUploadCheck != null) {
                afterUploadCheck.run();
            }

            log.info("文件写入并登记成功 taskId={}, fileName={}, size={}B, storagePath={}",
                    taskId, fileName, fileSize, storagePath);
            return FileUploadResponse.builder()
                    .taskId(taskId)
                    .fileName(fileName)
                    .fileSize(fileSize)
                    .status("PENDING")
                    .uploadTime(metadata.getUploadTime())
                    .build();
        } catch (RuntimeException e) {
            if (objectUploaded) {
                compensateObject(storagePath);
            }
            throw e;
        }
    }

    private void verifyStableSnapshot(Path sourcePath, long expectedSize, long expectedModifiedMillis)
            throws IOException {
        long actualSize = Files.size(sourcePath);
        long actualModifiedMillis = Files.getLastModifiedTime(sourcePath).toMillis();
        if (actualSize != expectedSize || actualModifiedMillis != expectedModifiedMillis) {
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

    private void compensateObject(String storagePath) {
        try {
            minioStorageService.deleteFile(null, storagePath);
            log.warn("数据库登记失败，已补偿删除 MinIO 对象 storagePath={}", storagePath);
        } catch (Exception cleanupException) {
            log.error("数据库登记失败且 MinIO 补偿删除失败，留下孤儿对象 storagePath={}: {}",
                    storagePath, cleanupException.getMessage(), cleanupException);
        }
    }
}
