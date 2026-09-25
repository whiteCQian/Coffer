package com.coffer.file.application;

import com.coffer.file.application.archive.StorageArchiveService;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.model.runtime.ModelRuntimeModeService;
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
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.tag.infrastructure.persistence.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 失败文件重试服务测试：仅 FAILED 可重试，重试后旧任务删除、新 PENDING 任务建立、
 * 文件状态/taskId/摘要重置、遗留标签关联清空。
 *
 * <p>{@link Transactional} 保证每个用例的种子数据自动回滚，互不污染。
 */
@SpringBootTest
@Transactional
class FileOperationServiceTest {

    @Autowired
    private FileOperationService fileOperationService;

    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    @Autowired
    private AsyncTaskRepository asyncTaskRepository;

    @Autowired
    private FileTagMappingRepository fileTagMappingRepository;

    @Autowired
    private TagRepository tagRepository;

    /**
     * 归档服务 Mock：改分类若触发真实归档会走 REQUIRES_NEW 独立提交，在 {@code @Transactional}
     * 测试中会越过回滚残留数据；归档侧行为（copy → 更新路径 → 删旧）由 StorageArchiveServiceTest
     * 单测覆盖，此处仅验证「改归档文件分类会调用 archive」这一编排。
     */
    @MockitoBean
    private StorageArchiveService storageArchiveService;

    /** C15 运行模式门禁在本测试中固定为已验证的 API 模式，避免调用真实模型连通性。 */
    @MockitoBean
    private ModelRuntimeModeService runtimeModeService;

    @BeforeEach
    void stubValidatedRuntimeMode() {
        when(runtimeModeService.requireActiveMode()).thenReturn(GovernanceRunMode.API);
    }

    @Test
    void retryResetsFileAndRebuildsPendingTaskAndClearsMappings() {
        String oldTaskId = "task-old-fail";
        FileMetadata fm = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("失败文档.pdf").fileSize(10L).fileType("pdf")
                .status(FileStatus.FAILED).taskId(oldTaskId).summary("上一次残留摘要")
                .storagePath("files/uuid.pdf").build());
        asyncTaskRepository.save(AsyncTask.builder().taskId(oldTaskId).fileName("失败文档.pdf")
                .status(AsyncTaskStatus.FAILED).progress(60).result("模型超时").build());
        // 上次失败尝试残留的标签关联（即便已 CONFIRMED）也应在重试时清空
        Long tagId = tagRepository.save(Tag.builder().tagName("合同").build()).getId();
        fileTagMappingRepository.save(FileTagMapping.builder().fileId(fm.getId()).tagId(tagId)
                .confirmationStatus(ConfirmationStatus.CONFIRMED).confirmedAt(LocalDateTime.now()).build());

        String newTaskId = fileOperationService.retryFile(fm.getId());

        assertThat(newTaskId).isNotEqualTo(oldTaskId).isNotBlank();
        // 文件回 PENDING，taskId 换新，摘要清空
        FileMetadata reloaded = fileMetadataRepository.findById(fm.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(FileStatus.PENDING);
        assertThat(reloaded.getTaskId()).isEqualTo(newTaskId);
        assertThat(reloaded.getSummary()).isNull();
        // 旧任务删除，新任务 PENDING/0 建立
        assertThat(asyncTaskRepository.findByTaskId(oldTaskId)).isEmpty();
        AsyncTask newTask = asyncTaskRepository.findByTaskId(newTaskId).orElseThrow();
        assertThat(newTask.getStatus()).isEqualTo(AsyncTaskStatus.PENDING);
        assertThat(newTask.getProgress()).isZero();
        assertThat(newTask.getFileName()).isEqualTo("失败文档.pdf");
        // 遗留标签关联清空；storagePath 不动（异步管道从原路径重读）
        assertThat(fileTagMappingRepository.findByFileId(fm.getId())).isEmpty();
        assertThat(reloaded.getStoragePath()).isEqualTo("files/uuid.pdf");
    }

    @Test
    void retryNonFailedThrows() {
        FileMetadata completed = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("已完成文档.pdf").fileSize(10L).fileType("pdf")
                .status(FileStatus.COMPLETED).build());

        assertThatThrownBy(() -> fileOperationService.retryFile(completed.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅失败文件可重试");
    }

    @Test
    void retryNotFoundThrows() {
        assertThatThrownBy(() -> fileOperationService.retryFile(999999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件不存在");
    }

    @Test
    void deleteRemovesMappingsTaskAndMetadataReturnsStoragePath() {
        String taskId = "task-del-1";
        FileMetadata fm = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("待删除合同.pdf").fileSize(10L).fileType("pdf")
                .status(FileStatus.COMPLETED).taskId(taskId).summary("摘要")
                .storagePath("contracts/del-uuid.pdf").build());
        asyncTaskRepository.save(AsyncTask.builder().taskId(taskId).fileName("待删除合同.pdf")
                .status(AsyncTaskStatus.COMPLETED).progress(100).result("摘要").build());
        Long tagId = tagRepository.save(Tag.builder().tagName("合同").build()).getId();
        fileTagMappingRepository.save(FileTagMapping.builder().fileId(fm.getId()).tagId(tagId)
                .confirmationStatus(ConfirmationStatus.CONFIRMED).confirmedAt(LocalDateTime.now()).build());

        String storagePath = fileOperationService.deleteFile(fm.getId());

        assertThat(storagePath).isEqualTo("contracts/del-uuid.pdf");
        assertThat(fileMetadataRepository.findById(fm.getId())).isEmpty();
        assertThat(asyncTaskRepository.findByTaskId(taskId)).isEmpty();
        assertThat(fileTagMappingRepository.findByFileId(fm.getId())).isEmpty();
    }

    @Test
    void deleteAnyStatusAllowed() {
        // 任意状态可删（含进行中）：异步线程随后查不到记录自然退出
        String taskId = "task-del-processing";
        FileMetadata fm = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("处理中文件.pdf").fileSize(10L).fileType("pdf")
                .status(FileStatus.PROCESSING).taskId(taskId).storagePath("files/p.pdf").build());
        asyncTaskRepository.save(AsyncTask.builder().taskId(taskId).fileName("处理中文件.pdf")
                .status(AsyncTaskStatus.PROCESSING).progress(10).build());

        String storagePath = fileOperationService.deleteFile(fm.getId());

        assertThat(storagePath).isEqualTo("files/p.pdf");
        assertThat(fileMetadataRepository.findById(fm.getId())).isEmpty();
        assertThat(asyncTaskRepository.findByTaskId(taskId)).isEmpty();
    }

    @Test
    void deleteNotFoundThrows() {
        assertThatThrownBy(() -> fileOperationService.deleteFile(999999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件不存在");
    }

    @Test
    void renameTrimsWhitespaceAndPersistsPreservingOtherFields() {
        FileMetadata fm = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("旧名称.pdf").fileSize(10L).fileType("pdf")
                .status(FileStatus.COMPLETED).category(CategoryType.CONTRACT).archived(true)
                .summary("既有摘要").storagePath("contracts/uuid.pdf").build());
        Long tagId = tagRepository.save(Tag.builder().tagName("合同").build()).getId();
        fileTagMappingRepository.save(FileTagMapping.builder().fileId(fm.getId()).tagId(tagId)
                .confirmationStatus(ConfirmationStatus.CONFIRMED).confirmedAt(LocalDateTime.now()).build());

        Long result = fileOperationService.renameFile(fm.getId(), "  新名称.pdf  ");

        assertThat(result).isEqualTo(fm.getId());
        FileMetadata reloaded = fileMetadataRepository.findById(fm.getId()).orElseThrow();
        // 去首尾空白后落库；分类/归档态/摘要/标签确认全部保留
        assertThat(reloaded.getFileName()).isEqualTo("新名称.pdf");
        assertThat(reloaded.getCategory()).isEqualTo(CategoryType.CONTRACT);
        assertThat(reloaded.isArchived()).isTrue();
        assertThat(reloaded.getSummary()).isEqualTo("既有摘要");
        assertThat(fileTagMappingRepository.findByFileId(fm.getId())).hasSize(1);
    }

    @Test
    void renameSameNameIsIdempotent() {
        FileMetadata fm = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("原名.pdf").fileSize(10L).fileType("pdf")
                .status(FileStatus.COMPLETED).archived(true).build());

        Long result = fileOperationService.renameFile(fm.getId(), "原名.pdf");

        assertThat(result).isEqualTo(fm.getId());
        // 归档态不被破坏（改名不涉及归档，不做任何存档操作）
        assertThat(fileMetadataRepository.findById(fm.getId()).orElseThrow().isArchived()).isTrue();
        verify(storageArchiveService, never()).archive(anyLong());
    }

    @Test
    void renameBlankThrows() {
        assertThatThrownBy(() -> fileOperationService.renameFile(1L, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件名不能为空");
    }

    @Test
    void renameTooLongThrows() {
        assertThatThrownBy(() -> fileOperationService.renameFile(1L, "x".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件名不能超过255个字符");
    }

    @Test
    void renameNotFoundThrows() {
        assertThatThrownBy(() -> fileOperationService.renameFile(999999L, "新名.pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件不存在");
    }

    @Test
    void changeCategoryUnarchivedUpdatesOnlyCategoryKeepingTagsSummaryAndConfirmations() {
        FileMetadata fm = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("合同.pdf").fileSize(10L).fileType("pdf")
                .status(FileStatus.COMPLETED).category(CategoryType.OTHER)
                .summary("合同摘要").storagePath("files/uuid.pdf").build());
        Long tagId = tagRepository.save(Tag.builder().tagName("合同").build()).getId();
        fileTagMappingRepository.save(FileTagMapping.builder().fileId(fm.getId()).tagId(tagId)
                .confirmationStatus(ConfirmationStatus.CONFIRMED).confirmedAt(LocalDateTime.now()).build());

        Long result = fileOperationService.changeCategory(fm.getId(), "CONTRACT");

        assertThat(result).isEqualTo(fm.getId());
        FileMetadata reloaded = fileMetadataRepository.findById(fm.getId()).orElseThrow();
        assertThat(reloaded.getCategory()).isEqualTo(CategoryType.CONTRACT);
        assertThat(reloaded.isArchived()).isFalse();
        assertThat(reloaded.getSummary()).isEqualTo("合同摘要");
        assertThat(reloaded.getFileName()).isEqualTo("合同.pdf");
        assertThat(reloaded.getStatus()).isEqualTo(FileStatus.COMPLETED);
        assertThat(fileTagMappingRepository.findByFileId(fm.getId())).hasSize(1);
        // 非归档文件：不触发对象搬移
        verify(storageArchiveService, never()).archive(anyLong());
    }

    @Test
    void changeCategoryArchivedFileUnarchivesAndTriggersArchive() {
        FileMetadata fm = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("旧归档.pdf").fileSize(10L).fileType("pdf")
                .status(FileStatus.COMPLETED).category(CategoryType.CONTRACT).archived(true)
                .summary("摘要").storagePath("contracts/old-uuid.pdf").build());

        Long result = fileOperationService.changeCategory(fm.getId(), "REPORT");

        assertThat(result).isEqualTo(fm.getId());
        // DB 层：category 已改、归档态解除（新分类目录的对象随后由归档重建）
        FileMetadata reloaded = fileMetadataRepository.findById(fm.getId()).orElseThrow();
        assertThat(reloaded.getCategory()).isEqualTo(CategoryType.REPORT);
        assertThat(reloaded.isArchived()).isFalse();
        // 编排层：事务外触发归档（真实归档行为由 StorageArchiveServiceTest 覆盖）
        verify(storageArchiveService).archive(fm.getId());
    }

    @Test
    void changeCategorySameCategoryIdempotentDoesNotUnarchive() {
        FileMetadata fm = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("归档合同.pdf").fileSize(10L).fileType("pdf")
                .status(FileStatus.COMPLETED).category(CategoryType.CONTRACT).archived(true)
                .storagePath("contracts/x.pdf").build());

        Long result = fileOperationService.changeCategory(fm.getId(), "CONTRACT");

        assertThat(result).isEqualTo(fm.getId());
        // 幂等：分类未变化，归档态原样保留，不触发移动
        FileMetadata reloaded = fileMetadataRepository.findById(fm.getId()).orElseThrow();
        assertThat(reloaded.isArchived()).isTrue();
        assertThat(reloaded.getCategory()).isEqualTo(CategoryType.CONTRACT);
        verify(storageArchiveService, never()).archive(anyLong());
    }

    @Test
    void changeCategoryNonCompletedThrows() {
        FileMetadata fm = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("处理中.pdf").fileSize(10L).fileType("pdf")
                .status(FileStatus.PROCESSING).build());

        assertThatThrownBy(() -> fileOperationService.changeCategory(fm.getId(), "REPORT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅已完成文件可更改分类");
    }

    @Test
    void changeCategoryInvalidCategoryThrows() {
        FileMetadata fm = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("合同.pdf").fileSize(10L).fileType("pdf")
                .status(FileStatus.COMPLETED).build());

        assertThatThrownBy(() -> fileOperationService.changeCategory(fm.getId(), "BOGUS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法分类参数: BOGUS");
    }

    @Test
    void changeCategoryNotFoundThrows() {
        assertThatThrownBy(() -> fileOperationService.changeCategory(999999L, "REPORT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件不存在");
    }
}
