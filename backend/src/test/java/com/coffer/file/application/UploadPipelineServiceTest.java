package com.coffer.file.application;

import com.coffer.file.application.parse.DocumentParseService;
import com.coffer.service.MinioStorageService;
import com.coffer.service.VisionModelService;
import com.coffer.task.application.AsyncTaskService;
import com.coffer.task.application.TaskRegistrationService;
import com.coffer.tag.api.dto.TagAndCategoryResult;
import com.coffer.dto.VisionResult;
import com.coffer.file.domain.CategoryType;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.tag.domain.Tag;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.tag.infrastructure.persistence.TagRepository;
import com.coffer.tool.TagGenerationTool;
import com.coffer.file.domain.parse.ParseResult;
import com.coffer.vector.VectorIndexingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UploadPipelineService} 管道分支单元测试（纯 Mockito，无 Spring）：
 * 手工构造 service（全 mock 依赖），直接同步调用 {@code processUploadPipeline}，验证：
 * 图片扩展名走视觉模型（传 base64 + mime + 文件名）、文本走 DocumentParseService 不调 vision、
 * 图片过大/空标签降级 FAILED、两分支汇合 finishProcessing（标签入库 + category 落库 + summary + 完成状态）。
 *
 * <p>因手工 {@code new} 构造，{@code @Async}/{@code @Transactional} 代理不生效，方法体同步执行，
 * 分支逻辑与参数传递可确定断言；真实仓库交互已在 {@code TagConfirmationServiceTest} 等覆盖。
 */
class UploadPipelineServiceTest {

    private MinioStorageService minioStorageService;
    private DocumentParseService documentParseService;
    private TagGenerationTool tagGenerationTool;
    private VisionModelService visionModelService;
    private FileMetadataRepository fileMetadataRepository;
    private TagRepository tagRepository;
    private FileTagMappingRepository fileTagMappingRepository;
    private TaskRegistrationService taskRegistrationService;
    private AsyncTaskService asyncTaskService;
    private VectorIndexingService vectorIndexingService;
    private UploadPipelineService service;

    @BeforeEach
    void setUp() {
        minioStorageService = mock(MinioStorageService.class);
        documentParseService = mock(DocumentParseService.class);
        tagGenerationTool = mock(TagGenerationTool.class);
        visionModelService = mock(VisionModelService.class);
        fileMetadataRepository = mock(FileMetadataRepository.class);
        tagRepository = mock(TagRepository.class);
        fileTagMappingRepository = mock(FileTagMappingRepository.class);
        taskRegistrationService = mock(TaskRegistrationService.class);
        asyncTaskService = mock(AsyncTaskService.class);
        vectorIndexingService = mock(VectorIndexingService.class);
        // 构造器参数顺序与字段声明顺序一致（@RequiredArgsConstructor）
        service = new UploadPipelineService(minioStorageService, documentParseService, tagGenerationTool,
                visionModelService, fileMetadataRepository, tagRepository, fileTagMappingRepository,
                taskRegistrationService, asyncTaskService, vectorIndexingService);

        // 通用默认：任务与文件可定位；save 返回入参同实例；标签去重视为不存在（每次新建）
        when(tagRepository.findByTagName(anyString())).thenReturn(Optional.empty());
        when(tagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private FileMetadata seedMetadata(String taskId, String fileName, String fileType, Long fileSize) {
        FileMetadata metadata = FileMetadata.builder()
                .fileName(fileName)
                .fileSize(fileSize)
                .fileType(fileType)
                .storagePath("files/x/" + fileName)
                .taskId(taskId)
                .build();
        metadata.setId(1L);
        when(fileMetadataRepository.findByTaskId(taskId)).thenReturn(Optional.of(metadata));
        return metadata;
    }

    @Test
    void imageFileUsesVisionModelWithBase64AndMimeAndPersistsResult() {
        FileMetadata metadata = seedMetadata("t1", "photo.jpg", "jpg", 1000L);
        byte[] bytes = "fake-image-bytes".getBytes();
        when(minioStorageService.getFileStream(any(), anyString()))
                .thenReturn(new ByteArrayInputStream(bytes));
        when(visionModelService.describeImage(anyString(), anyString(), anyString()))
                .thenReturn(new VisionResult(CategoryType.IMAGE, List.of("风景", "天空"), "蓝天白云"));

        service.processUploadPipeline("t1");

        ArgumentCaptor<String> base64Captor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> mimeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(visionModelService).describeImage(base64Captor.capture(), mimeCaptor.capture(), nameCaptor.capture());
        assertThat(base64Captor.getValue()).isEqualTo(Base64.getEncoder().encodeToString(bytes));
        assertThat(mimeCaptor.getValue()).isEqualTo("image/jpeg");
        assertThat(nameCaptor.getValue()).isEqualTo("photo.jpg");

        // 图片不触发文本解析
        verify(documentParseService, never()).extractTextFromFile(anyString(), any());
        // 标签入库（2 个新标签 → 2 次 save）+ 关联批量保存
        verify(tagRepository, times(2)).save(any());
        verify(fileTagMappingRepository).saveAll(any());
        // 完成：摘要 = 视觉描述，category = 视觉分类，文件状态 COMPLETED
        verify(asyncTaskService).markAsCompleted(eq("t1"), eq("蓝天白云"));
        assertThat(metadata.getCategory()).isEqualTo(CategoryType.IMAGE);
        assertThat(metadata.getStatus()).isEqualTo(FileStatus.COMPLETED);
        assertThat(metadata.getSummary()).isEqualTo("蓝天白云");
    }

    @Test
    void imageMimeDerivedFromExtension() {
        // png → image/png；gif → image/gif（GIF 按图片走）
        FileMetadata metadata = seedMetadata("t2", "pic.png", "png", 100L);
        when(minioStorageService.getFileStream(any(), anyString()))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(visionModelService.describeImage(anyString(), anyString(), anyString()))
                .thenReturn(new VisionResult(CategoryType.OTHER, List.of("占位"), "描述"));

        service.processUploadPipeline("t2");

        ArgumentCaptor<String> mimeCaptor = ArgumentCaptor.forClass(String.class);
        verify(visionModelService).describeImage(anyString(), mimeCaptor.capture(), anyString());
        assertThat(mimeCaptor.getValue()).isEqualTo("image/png");
    }

    @Test
    void textFileUsesTextPipelineAndNotVision() {
        FileMetadata metadata = seedMetadata("t3", "doc.txt", "txt", 100L);
        String content = "这是一份合同文本内容";
        when(minioStorageService.getFileStream(any(), anyString()))
                .thenReturn(new ByteArrayInputStream(content.getBytes()));
        when(documentParseService.extractTextFromFile(anyString(), any()))
                .thenReturn(ParseResult.success(content));
        when(tagGenerationTool.generateTagAndCategory(content))
                .thenReturn(new TagAndCategoryResult(CategoryType.CONTRACT, List.of("合同", "销售")));

        service.processUploadPipeline("t3");

        verify(visionModelService, never()).describeImage(anyString(), anyString(), anyString());
        verify(documentParseService).extractTextFromFile(eq("doc.txt"), any());
        verify(tagGenerationTool).generateTagAndCategory(content);
        verify(asyncTaskService).markAsCompleted(eq("t3"), eq(content));
        assertThat(metadata.getCategory()).isEqualTo(CategoryType.CONTRACT);
        assertThat(metadata.getStatus()).isEqualTo(FileStatus.COMPLETED);
        assertThat(metadata.getSummary()).isEqualTo(content);
    }

    @Test
    void vectorIndexFailureDoesNotChangeSuccessfulUploadStatus() {
        FileMetadata metadata = seedMetadata("t6", "offline.txt", "txt", 100L);
        String content = "Redis 离线时文件仍应正常完成";
        when(minioStorageService.getFileStream(any(), anyString()))
                .thenReturn(new ByteArrayInputStream(content.getBytes()));
        when(documentParseService.extractTextFromFile(anyString(), any()))
                .thenReturn(ParseResult.success(content));
        when(tagGenerationTool.generateTagAndCategory(content))
                .thenReturn(new TagAndCategoryResult(CategoryType.OTHER, List.of("离线测试")));
        when(vectorIndexingService.indexParsedText(metadata, content)).thenReturn(false);

        service.processUploadPipeline("t6");

        verify(vectorIndexingService).indexParsedText(metadata, content);
        verify(asyncTaskService).markAsCompleted("t6", content);
        verify(asyncTaskService, never()).markAsFailed(eq("t6"), anyString());
        assertThat(metadata.getStatus()).isEqualTo(FileStatus.COMPLETED);
        assertThat(metadata.getVectorIndexedAt()).isNull();
    }

    @Test
    void imageLargerThan10MbFailsTask() {
        FileMetadata metadata = seedMetadata("t4", "big.png", "png", 11L * 1024 * 1024);
        service.processUploadPipeline("t4");

        verify(visionModelService, never()).describeImage(anyString(), anyString(), anyString());
        verify(asyncTaskService).markAsFailed(eq("t4"), argThat(msg -> msg != null && msg.contains("图片过大")));
        assertThat(metadata.getStatus()).isEqualTo(FileStatus.FAILED);
    }

    @Test
    void emptyTagsFromVisionFailsTask() {
        FileMetadata metadata = seedMetadata("t5", "photo.png", "png", 100L);
        when(minioStorageService.getFileStream(any(), anyString()))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(visionModelService.describeImage(anyString(), anyString(), anyString()))
                .thenReturn(new VisionResult(CategoryType.OTHER, List.of(), ""));

        service.processUploadPipeline("t5");

        verify(asyncTaskService).markAsFailed(eq("t5"), argThat(msg -> msg != null && msg.contains("无有效标签")));
        assertThat(metadata.getStatus()).isEqualTo(FileStatus.FAILED);
    }
}
