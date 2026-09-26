package com.coffer.file.api;

import com.coffer.file.application.async.AsyncFileProcessor;
import com.coffer.file.api.dto.FileDetailResponse;
import com.coffer.file.api.dto.FileUploadRequest;
import com.coffer.task.domain.AsyncTask;
import com.coffer.task.domain.AsyncTaskStatus;
import com.coffer.file.domain.CategoryType;
import com.coffer.tag.domain.ConfirmationStatus;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.tag.domain.FileTagMapping;
import com.coffer.tag.domain.Tag;
import com.coffer.task.infrastructure.persistence.AsyncTaskRepository;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.infrastructure.persistence.StorageDeletionTaskRepository;
import com.coffer.file.domain.StorageDeletionStatus;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.tag.infrastructure.persistence.TagRepository;
import com.coffer.file.application.FileService;
import com.coffer.file.application.FileUploadApplicationService;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.model.runtime.ModelRuntimeModeService;
import com.coffer.service.MinioStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件列表接口测试：验证端点、关键词透传与 {@code @PageableDefault} 默认分页。
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Transactional
class FileControllerTest extends com.coffer.auth.OwnerModelSubmissionTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FileService fileService;

    /** 上传链路外部依赖 Mock：MinIO 不真实上传，异步管道不真实执行。 */
    @MockitoBean
    private MinioStorageService minioStorageService;

    @MockitoBean
    private FileUploadApplicationService fileUploadApplicationService;

    @MockitoBean
    private AsyncFileProcessor asyncFileProcessor;

    /** C15 运行模式门禁在本测试中固定为已验证的 API 模式，避免调用真实模型连通性。 */
    @MockitoBean
    private ModelRuntimeModeService runtimeModeService;

    /** 真实 H2 仓储，用于校验上传后元数据/任务确实落库。 */
    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    @Autowired
    private AsyncTaskRepository asyncTaskRepository;

    @Autowired
    private StorageDeletionTaskRepository storageDeletionTaskRepository;

    @Autowired
    private FileTagMappingRepository fileTagMappingRepository;

    @Autowired
    private TagRepository tagRepository;

    @BeforeEach
    void stubValidatedRuntimeMode() {
        when(runtimeModeService.requireActiveMode()).thenReturn(GovernanceRunMode.API);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listFilesWithKeywordAndPaging() throws Exception {
        when(fileService.listFiles(nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), any(Pageable.class))).thenReturn(Page.empty());

        mockMvc.perform(get("/api/files")
                        .param("keyword", "合同")
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<String> kw = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Pageable> pg = ArgumentCaptor.forClass(Pageable.class);
        verify(fileService).listFiles(kw.capture(), nullable(String.class), nullable(String.class),
                nullable(String.class), pg.capture());
        assertThat(kw.getValue()).isEqualTo("合同");
        assertThat(pg.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pg.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listFilesDefaultsToSize10() throws Exception {
        when(fileService.listFiles(nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), any(Pageable.class))).thenReturn(Page.empty());

        mockMvc.perform(get("/api/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<Pageable> pg = ArgumentCaptor.forClass(Pageable.class);
        verify(fileService).listFiles(nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), pg.capture());
        assertThat(pg.getValue()).isInstanceOf(PageRequest.class);
        assertThat(pg.getValue().getPageSize()).isEqualTo(10);
        assertThat(pg.getValue().getPageNumber()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void listFilesForwardsCategoryAndSort() throws Exception {
        when(fileService.listFiles(nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), any(Pageable.class))).thenReturn(Page.empty());

        mockMvc.perform(get("/api/files")
                        .param("category", "CONTRACT")
                        .param("sort", "size"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<String> kw = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> cat = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sort = ArgumentCaptor.forClass(String.class);
        verify(fileService).listFiles(kw.capture(), cat.capture(), nullable(String.class), sort.capture(),
                any(Pageable.class));
        assertThat(kw.getValue()).isNull();
        assertThat(cat.getValue()).isEqualTo("CONTRACT");
        assertThat(sort.getValue()).isEqualTo("size");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listFilesInvalidSortReturns400() throws Exception {
        when(fileService.listFiles(nullable(String.class), nullable(String.class), nullable(String.class),
                eq("bogus"), any(Pageable.class)))
                .thenThrow(new IllegalArgumentException("非法排序参数: bogus"));

        mockMvc.perform(get("/api/files").param("sort", "bogus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("非法排序参数: bogus"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listFilesInvalidCategoryReturns400() throws Exception {
        when(fileService.listFiles(nullable(String.class), eq("NOPE"), nullable(String.class),
                nullable(String.class), any(Pageable.class)))
                .thenThrow(new IllegalArgumentException("非法分类参数: NOPE"));

        mockMvc.perform(get("/api/files").param("category", "NOPE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("非法分类参数: NOPE"));
    }

    @Test
    void retryFailedFileResetsAndTriggersAsync() throws Exception {
        String oldTaskId = "upload-retry-fail";
        FileMetadata fm = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("损坏文件.pdf").fileSize(12L).fileType("pdf")
                .status(FileStatus.FAILED).taskId(oldTaskId).summary("旧摘要")
                .storagePath("files/retry.pdf").build());
        asyncTaskRepository.save(AsyncTask.builder().taskId(oldTaskId).fileName("损坏文件.pdf")
                .status(AsyncTaskStatus.FAILED).progress(70).result("解析异常").build());

        mockMvc.perform(post("/api/files/" + fm.getId() + "/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 异步管道以新 taskId 触发
        ArgumentCaptor<String> taskIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(asyncFileProcessor).processFileAsync(taskIdCaptor.capture());
        String newTaskId = taskIdCaptor.getValue();
        assertThat(newTaskId).isNotEqualTo(oldTaskId).isNotBlank();
        // 新任务 PENDING/0 且关联到该文件，文件已回 PENDING、摘要清空
        AsyncTask newTask = asyncTaskRepository.findByTaskId(newTaskId).orElseThrow();
        assertThat(newTask.getStatus()).isEqualTo(AsyncTaskStatus.PENDING);
        assertThat(newTask.getProgress()).isZero();
        assertThat(newTask.getFileName()).isEqualTo("损坏文件.pdf");
        FileMetadata reloaded = fileMetadataRepository.findById(fm.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(FileStatus.PENDING);
        assertThat(reloaded.getTaskId()).isEqualTo(newTaskId);
        assertThat(reloaded.getSummary()).isNull();
    }

    @Test
    void retryNonFailedFileReturns400() throws Exception {
        FileMetadata completed = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("已完成.pdf").fileSize(12L).fileType("pdf")
                .status(FileStatus.COMPLETED).build());

        mockMvc.perform(post("/api/files/" + completed.getId() + "/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("仅失败文件可重试"));

        verify(asyncFileProcessor, never()).processFileAsync(anyString());
    }

    @Test
    void deleteExistingFileRemovesDbAndDeletesMinioObject() throws Exception {
        String taskId = "task-del";
        FileMetadata fm = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("待删合同.pdf").fileSize(12L).fileType("pdf")
                .status(FileStatus.COMPLETED).taskId(taskId).summary("摘要")
                .storagePath(ownerPath("archive/contracts/del-uuid.pdf")).build());
        asyncTaskRepository.save(AsyncTask.builder().taskId(taskId).fileName("待删合同.pdf")
                .status(AsyncTaskStatus.COMPLETED).progress(100).result("摘要").build());
        Long tagId = tagRepository.save(Tag.builder().tagName("合同").build()).getId();
        fileTagMappingRepository.save(FileTagMapping.builder().fileId(fm.getId()).tagId(tagId)
                .confirmationStatus(ConfirmationStatus.CONFIRMED).confirmedAt(java.time.LocalDateTime.now()).build());

        mockMvc.perform(delete("/api/files/" + fm.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // DB 与待清理意图同事务提交；对象存储由后台任务重试删除。
        var cleanup = storageDeletionTaskRepository.findAll().stream()
                .filter(task -> task.getFileId().equals(fm.getId())).findFirst().orElseThrow();
        assertThat(cleanup.getObjectPath()).isEqualTo(ownerPath("archive/contracts/del-uuid.pdf"));
        assertThat(cleanup.getStatus()).isEqualTo(StorageDeletionStatus.PENDING);
        verify(minioStorageService, never()).deleteFile(anyString(), anyString());
        assertThat(fileMetadataRepository.findById(fm.getId())).isEmpty();
        assertThat(asyncTaskRepository.findByTaskId(taskId)).isEmpty();
        assertThat(fileTagMappingRepository.findByFileId(fm.getId())).isEmpty();
    }

    @Test
    void deleteNonexistentFileReturns404() throws Exception {
        mockMvc.perform(delete("/api/files/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("资源不存在"));

        verify(minioStorageService, never()).deleteFile(anyString(), anyString());
    }

    @Test
    void deleteRegistersDurableStorageCleanup() throws Exception {
        FileMetadata fm = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("孤儿源.pdf").fileSize(12L).fileType("pdf")
                .status(FileStatus.FAILED).storagePath(ownerPath("files/orphan.pdf")).build());
        mockMvc.perform(delete("/api/files/" + fm.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 请求成功并写入 durable cleanup；外部对象删除由后台重试。
        assertThat(fileMetadataRepository.findById(fm.getId())).isEmpty();
        assertThat(storageDeletionTaskRepository.findAll()).anySatisfy(task -> {
            assertThat(task.getFileId()).isEqualTo(fm.getId());
            assertThat(task.getStatus()).isEqualTo(StorageDeletionStatus.PENDING);
        });
        verify(minioStorageService, never()).deleteFile(anyString(), anyString());
    }

    @Test
    void uploadFileDelegatesToApplicationServiceAndPreservesResponse() throws Exception {
        MockMultipartFile multipart = new MockMultipartFile(
                "file", "需求报告.pdf", "application/pdf", "fake-content".getBytes(StandardCharsets.UTF_8));

        when(fileUploadApplicationService.upload(any(FileUploadRequest.class)))
                .thenReturn(com.coffer.file.api.dto.FileUploadResponse.builder()
                        .taskId("task-1")
                        .fileName("需求报告.pdf")
                        .fileSize(12L)
                        .status("PENDING")
                        .build());

        mockMvc.perform(multipart("/api/files/upload").file(multipart))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value("task-1"))
                .andExpect(jsonPath("$.data.fileName").value("需求报告.pdf"))
                .andExpect(jsonPath("$.data.fileSize").value(12))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        verify(fileUploadApplicationService).upload(any(FileUploadRequest.class));
    }

    @Test
    void uploadFileMissingFileReturns400() throws Exception {
        mockMvc.perform(multipart(HttpMethod.POST, "/api/files/upload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void uploadFileOver50MibReturns400() throws Exception {
        // 自定义 MultipartFile：getSize 超过 50MB 上限，避免真实分配 50MB 堆内存
        MockMultipartFile oversized = new MockMultipartFile(
                "file", "big.bin", "application/octet-stream", new byte[0]) {
            @Override
            public long getSize() {
                return FileUploadRequest.MAX_FILE_SIZE + 1;
            }
        };

        mockMvc.perform(multipart("/api/files/upload").file(oversized))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void getFileDetailReturnsDetail() throws Exception {
        when(fileService.getFileDetail(1L))
                .thenReturn(FileDetailResponse.builder().id(1L).fileName("详情.pdf").build());

        mockMvc.perform(get("/api/files/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.fileName").value("详情.pdf"));
    }

    @Test
    void getFileDetailNotFoundReturns404() throws Exception {
        when(fileService.getFileDetail(999L))
                .thenThrow(new IllegalArgumentException("文件不存在: 999"));

        mockMvc.perform(get("/api/files/999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("文件不存在: 999"));
    }

    @Test
    void renameFileUpdatesDbAndReturnsDetail() throws Exception {
        FileMetadata fm = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("旧名称.pdf").fileSize(12L).fileType("pdf")
                .status(FileStatus.COMPLETED).build());
        when(fileService.getFileDetail(fm.getId())).thenReturn(FileDetailResponse.builder()
                .id(fm.getId()).fileName("新名称.pdf").build());

        mockMvc.perform(patch("/api/files/" + fm.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"  新名称.pdf  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.fileName").value("新名称.pdf"));

        // 服务端已去首尾空白落库
        assertThat(fileMetadataRepository.findById(fm.getId()).orElseThrow().getFileName())
                .isEqualTo("新名称.pdf");
    }

    @Test
    void renameFileBlankNameReturnsBadRequest() throws Exception {
        mockMvc.perform(patch("/api/files/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("fileName: 文件名不能为空"));
    }

    @Test
    void renameFileNotFoundReturns404() throws Exception {
        mockMvc.perform(patch("/api/files/999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"不存在.pdf\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("资源不存在"));
    }

    @Test
    void directCategoryChangeIsRejectedAndLeavesFileUntouched() throws Exception {
        FileMetadata fm = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("合同.pdf").fileSize(12L).fileType("pdf")
                .status(FileStatus.COMPLETED).category(CategoryType.OTHER).archived(false)
                .build());

        mockMvc.perform(put("/api/files/" + fm.getId() + "/category")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"CONTRACT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.msg").value("分类变更必须通过整理预览确认"));

        FileMetadata reloaded = fileMetadataRepository.findById(fm.getId()).orElseThrow();
        assertThat(reloaded.getCategory()).isEqualTo(CategoryType.OTHER);
        assertThat(reloaded.isArchived()).isFalse();
    }
}
