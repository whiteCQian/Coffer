package com.coffer.file.application;

import com.coffer.file.domain.service.FileTypeResolver;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.file.api.dto.FileUploadRequest;
import com.coffer.file.api.dto.FileUploadResponse;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileWriteIntent;
import com.coffer.file.domain.FileWriteIntentStatus;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.application.event.FileUploadedEvent;
import com.coffer.file.infrastructure.storage.PathGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.test.util.ReflectionTestUtils;

class FileUploadApplicationServiceTest {

    private FileStoragePort storage;
    private FileWriteIntentService intents;
    private FileTypeResolver fileTypeResolver;
    private PathGenerator pathGenerator;
    private UploadPipelineService uploadPipelineService;
    private ApplicationEventPublisher eventPublisher;
    private FileUploadApplicationService service;

    @org.junit.jupiter.api.AfterEach void clearOwner() { com.coffer.auth.service.TenantContext.clear(); }
    @BeforeEach
    void setUp() {
        com.coffer.auth.service.TenantContext.set(7L);
        storage = mock(FileStoragePort.class);
        intents = mock(FileWriteIntentService.class);
        fileTypeResolver = new FileTypeResolver();
        pathGenerator = mock(PathGenerator.class);
        uploadPipelineService = mock(UploadPipelineService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new FileUploadApplicationService(storage, fileTypeResolver,
                pathGenerator, uploadPipelineService, eventPublisher, intents);
    }

    @Test
    void uploadsRegistersAndPublishesAfterRegistration() {
        FileUploadRequest request = request("需求报告.PDF", "content");
        FileMetadata metadata = FileMetadata.builder().fileName("需求报告.PDF").build();
        when(pathGenerator.generateStoragePath("需求报告.PDF", "pdf"))
                .thenReturn("files/2026/01/01/pdf/path_需求报告.PDF");
        String sha = sha256("content");
        when(storage.write(eq("files/2026/01/01/pdf/path_需求报告.PDF"), any(InputStream.class),
                eq("text/plain"), eq(7L))).thenReturn(new FileStoragePort.StoredObject(
                "files/2026/01/01/pdf/path_需求报告.PDF", 7L, sha, "etag"));
        when(uploadPipelineService.registerUploadTask(anyString(), eq("需求报告.PDF"), eq("pdf"),
                eq("files/2026/01/01/pdf/path_需求报告.PDF"), eq(7L), eq(sha))).thenReturn(metadata);

        FileUploadResponse response = service.upload(request);

        assertThat(response.getFileName()).isEqualTo("需求报告.PDF");
        assertThat(response.getFileSize()).isEqualTo(7L);
        assertThat(response.getStatus()).isEqualTo("PENDING");
        var order = inOrder(intents, storage, uploadPipelineService);
        order.verify(intents).begin(anyString(), anyString(), eq("UPLOAD"),
                eq("files/2026/01/01/pdf/path_需求报告.PDF"), eq("需求报告.PDF"),
                eq("pdf"), eq("text/plain"), eq(7L), isNull());
        order.verify(storage).write(eq("files/2026/01/01/pdf/path_需求报告.PDF"),
                any(InputStream.class), eq("text/plain"), eq(7L));
        order.verify(intents).objectWritten(anyString(), any(FileStoragePort.StoredObject.class));
        order.verify(uploadPipelineService).registerUploadTask(anyString(), eq("需求报告.PDF"),
                eq("pdf"), eq("files/2026/01/01/pdf/path_需求报告.PDF"), eq(7L), eq(sha));
        verify(intents).registered(anyString());
        verify(eventPublisher).publishEvent(any(FileUploadedEvent.class));
    }

    @Test
    void registrationFailureLeavesDurableIntentForReconciliation() {
        FileUploadRequest request = request("failed.txt", "content");
        String storagePath = "files/2026/01/01/txt/path_failed.txt";
        when(pathGenerator.generateStoragePath("failed.txt", "txt")).thenReturn(storagePath);
        String sha = sha256("content");
        when(storage.write(eq(storagePath), any(InputStream.class), eq("text/plain"), eq(7L)))
                .thenReturn(new FileStoragePort.StoredObject(storagePath, 7L, sha, "etag"));
        doThrow(new RuntimeException("database unavailable"))
                .when(uploadPipelineService).registerUploadTask(anyString(), eq("failed.txt"), eq("txt"),
                        eq(storagePath), eq(7L), eq(sha));

        assertThatThrownBy(() -> service.upload(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("database unavailable");

        verify(intents).objectWritten(anyString(), any(FileStoragePort.StoredObject.class));
        verify(intents).failed(anyString(), eq("RuntimeException"));
        verify(storage, never()).delete(anyString(), anyString());
    }

    private FileUploadRequest request(String filename, String content) {
        return FileUploadRequest.builder()
                .file(new MockMultipartFile("file", filename, "text/plain", content.getBytes()))
                .build();
    }

    @Test
    void retryWithSameKeyReturnsOriginalTaskWithoutWritingAgain() {
        String key = UUID.randomUUID().toString();
        String operationId = UUID.nameUUIDFromBytes(("upload:7:" + key)
                .getBytes(StandardCharsets.UTF_8)).toString();
        String storagePath = "files/2026/01/01/txt/path_report.txt";
        String sha = sha256("content");
        FileMetadata metadata = FileMetadata.builder().fileName("report.txt").build();
        when(pathGenerator.generateStoragePath("report.txt", "txt")).thenReturn(storagePath);
        when(storage.write(eq(storagePath), any(InputStream.class), eq("text/plain"), eq(7L)))
                .thenReturn(new FileStoragePort.StoredObject(storagePath, 7L, sha, "etag"));
        when(uploadPipelineService.registerUploadTask(anyString(), eq("report.txt"), eq("txt"),
                eq(storagePath), eq(7L), eq(sha))).thenReturn(metadata);
        FileWriteIntent intent = new FileWriteIntent();
        intent.setId(operationId);
        intent.setKind("UPLOAD");
        intent.setFileName("report.txt");
        intent.setDeclaredSize(7L);
        intent.setContentSha256(sha);
        intent.setStatus(FileWriteIntentStatus.REGISTERED);
        when(intents.existing(operationId)).thenReturn(Optional.empty(), Optional.of(intent));
        FileMetadataRepository files = mock(FileMetadataRepository.class);
        ReflectionTestUtils.setField(service, "fileMetadataRepository", files);

        FileUploadResponse first = service.upload(request("report.txt", "content"), key);
        intent.setTaskId(first.getTaskId());
        when(files.findByTaskId(first.getTaskId())).thenReturn(Optional.of(metadata));
        FileUploadResponse second = service.upload(request("report.txt", "content"), key);

        assertThat(second.getTaskId()).isEqualTo(first.getTaskId());
        assertThat(first.getFileName()).isEqualTo(second.getFileName());
        verify(storage).write(eq(storagePath), any(InputStream.class), eq("text/plain"), eq(7L));
    }

    @Test
    void retryWithSameKeyRejectsDifferentBody() {
        String key = UUID.randomUUID().toString();
        String operationId = UUID.nameUUIDFromBytes(("upload:7:" + key)
                .getBytes(StandardCharsets.UTF_8)).toString();
        FileWriteIntent intent = new FileWriteIntent();
        intent.setKind("UPLOAD");
        intent.setTaskId("original-task");
        intent.setFileName("report.txt");
        intent.setDeclaredSize(7L);
        intent.setContentSha256(sha256("content"));
        intent.setStatus(FileWriteIntentStatus.REGISTERED);
        when(intents.existing(operationId)).thenReturn(Optional.of(intent));

        assertThatThrownBy(() -> service.upload(request("report.txt", "changed"), key))
                .isInstanceOf(com.coffer.file.storage.StorageConflictException.class);
        verify(storage, never()).write(anyString(), any(InputStream.class), anyString(), anyLong());
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
