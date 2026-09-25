package com.coffer.file.application;

import com.coffer.file.domain.service.FileTypeResolver;
import com.coffer.service.MinioStorageService;
import com.coffer.file.api.dto.FileUploadRequest;
import com.coffer.file.api.dto.FileUploadResponse;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.application.event.FileUploadedEvent;
import com.coffer.file.infrastructure.storage.PathGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileUploadApplicationServiceTest {

    private MinioStorageService minioStorageService;
    private FileTypeResolver fileTypeResolver;
    private PathGenerator pathGenerator;
    private UploadPipelineService uploadPipelineService;
    private ApplicationEventPublisher eventPublisher;
    private FileUploadApplicationService service;

    @BeforeEach
    void setUp() {
        minioStorageService = mock(MinioStorageService.class);
        fileTypeResolver = new FileTypeResolver();
        pathGenerator = mock(PathGenerator.class);
        uploadPipelineService = mock(UploadPipelineService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new FileUploadApplicationService(minioStorageService, fileTypeResolver,
                pathGenerator, uploadPipelineService, eventPublisher);
    }

    @Test
    void uploadsRegistersAndPublishesAfterRegistration() {
        FileUploadRequest request = request("需求报告.PDF", "content");
        FileMetadata metadata = FileMetadata.builder().fileName("需求报告.PDF").build();
        when(pathGenerator.generateStoragePath("需求报告.PDF", "pdf"))
                .thenReturn("files/2026/01/01/pdf/path_需求报告.PDF");
        when(uploadPipelineService.registerUploadTask(anyString(), eq("需求报告.PDF"), eq("pdf"),
                eq("files/2026/01/01/pdf/path_需求报告.PDF"), eq(7L))).thenReturn(metadata);

        FileUploadResponse response = service.upload(request);

        assertThat(response.getFileName()).isEqualTo("需求报告.PDF");
        assertThat(response.getFileSize()).isEqualTo(7L);
        assertThat(response.getStatus()).isEqualTo("PENDING");
        verify(minioStorageService).uploadFile(isNull(), eq("files/2026/01/01/pdf/path_需求报告.PDF"),
                any(InputStream.class), eq("text/plain"), eq(7L));
        verify(eventPublisher).publishEvent(any(FileUploadedEvent.class));
    }

    @Test
    void registrationFailureCompensatesUploadedObject() {
        FileUploadRequest request = request("failed.txt", "content");
        String storagePath = "files/2026/01/01/txt/path_failed.txt";
        when(pathGenerator.generateStoragePath("failed.txt", "txt")).thenReturn(storagePath);
        doThrow(new RuntimeException("database unavailable"))
                .when(uploadPipelineService).registerUploadTask(anyString(), eq("failed.txt"), eq("txt"),
                        eq(storagePath), eq(7L));

        assertThatThrownBy(() -> service.upload(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("database unavailable");

        verify(minioStorageService).deleteFile(isNull(), eq(storagePath));
    }

    private FileUploadRequest request(String filename, String content) {
        return FileUploadRequest.builder()
                .file(new MockMultipartFile("file", filename, "text/plain", content.getBytes()))
                .build();
    }
}
