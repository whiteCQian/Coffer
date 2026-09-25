package com.coffer.task.api;

import com.coffer.task.domain.AsyncTask;
import com.coffer.task.domain.AsyncTaskStatus;
import com.coffer.tag.domain.ConfirmationStatus;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.tag.domain.FileTagMapping;
import com.coffer.tag.domain.Tag;
import com.coffer.task.infrastructure.persistence.AsyncTaskRepository;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.tag.infrastructure.persistence.TagRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务进度查询接口测试：验证进度快照映射与不存在时的 404。
 *
 * <p>{@link Transactional} 保证种子任务自动回滚，互不污染。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AsyncTaskRepository asyncTaskRepository;

    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    @Autowired
    private FileTagMappingRepository fileTagMappingRepository;

    @Autowired
    private TagRepository tagRepository;

    @Test
    void getOverviewAggregatesProcessingFailedAndPendingConfirm() throws Exception {
        // 处理中任务 ×2
        asyncTaskRepository.save(AsyncTask.builder().taskId("overview-proc-1").fileName("需求报告.pdf")
                .status(AsyncTaskStatus.PROCESSING).progress(42).build());
        asyncTaskRepository.save(AsyncTask.builder().taskId("overview-proc-2").fileName("架构图.png")
                .status(AsyncTaskStatus.PROCESSING).progress(88).build());
        // 失败任务 ×1：result 字段承载错误信息
        asyncTaskRepository.save(AsyncTask.builder().taskId("overview-fail-1").fileName("损坏文件.bin")
                .status(AsyncTaskStatus.FAILED).progress(60).result("解析超时").build());

        Long tagId = tagRepository.save(Tag.builder().tagName("合同").build()).getId();
        // 已完成 + 待确认标签 → 入「待确认」面板
        FileMetadata pending = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("待确认合同.pdf").fileSize(10L).fileType("pdf").status(FileStatus.COMPLETED).build());
        fileTagMappingRepository.save(FileTagMapping.builder().fileId(pending.getId()).tagId(tagId)
                .confirmationStatus(ConfirmationStatus.PENDING_CONFIRMATION).build());
        // 已完成 + 仅已确认标签 → 不入列
        FileMetadata confirmedOnly = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("已确认发票.pdf").fileSize(10L).fileType("pdf").status(FileStatus.COMPLETED).build());
        fileTagMappingRepository.save(FileTagMapping.builder().fileId(confirmedOnly.getId()).tagId(tagId)
                .confirmationStatus(ConfirmationStatus.CONFIRMED).build());
        // 未完成（PROCESSING）+ 待确认标签 → 状态不满足，不入列
        FileMetadata notCompleted = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("处理中文件.pdf").fileSize(10L).fileType("pdf").status(FileStatus.PROCESSING).build());
        fileTagMappingRepository.save(FileTagMapping.builder().fileId(notCompleted.getId()).tagId(tagId)
                .confirmationStatus(ConfirmationStatus.PENDING_CONFIRMATION).build());

        mockMvc.perform(get("/api/tasks/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.processingCount").value(2))
                .andExpect(jsonPath("$.data.failedCount").value(1))
                .andExpect(jsonPath("$.data.pendingConfirmCount").value(1))
                .andExpect(jsonPath("$.data.processing.length()").value(2))
                .andExpect(jsonPath("$.data.failed.length()").value(1))
                .andExpect(jsonPath("$.data.pendingConfirm.length()").value(1))
                // 处理中明细字段透传（按 taskId 定位，不依赖 created_at 先后）
                .andExpect(jsonPath("$.data.processing[?(@.taskId == 'overview-proc-1')].progress").value(42))
                .andExpect(jsonPath("$.data.processing[?(@.taskId == 'overview-proc-2')].fileName").value("架构图.png"))
                // 失败明细 error 取任务 result
                .andExpect(jsonPath("$.data.failed[?(@.taskId == 'overview-fail-1')].error").value("解析超时"))
                // 待确认明细仅剩那个「已完成 + 待确认标签」的文件
                .andExpect(jsonPath("$.data.pendingConfirm[0].fileName").value("待确认合同.pdf"));
    }

    @Test
    void getTaskProgressReturnsSnapshot() throws Exception {
        asyncTaskRepository.save(AsyncTask.builder()
                .taskId("task-progress-1")
                .fileName("需求报告.pdf")
                .status(AsyncTaskStatus.PROCESSING)
                .progress(50)
                .build());

        mockMvc.perform(get("/api/tasks/task-progress-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value("task-progress-1"))
                .andExpect(jsonPath("$.data.fileName").value("需求报告.pdf"))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.progress").value(50));
    }

    @Test
    void getTaskProgressCompletedCarriesResult() throws Exception {
        asyncTaskRepository.save(AsyncTask.builder()
                .taskId("task-progress-2")
                .fileName("合同.txt")
                .status(AsyncTaskStatus.COMPLETED)
                .progress(100)
                .result("这是摘要内容")
                .build());

        mockMvc.perform(get("/api/tasks/task-progress-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.progress").value(100))
                .andExpect(jsonPath("$.data.result").value("这是摘要内容"));
    }

    @Test
    void getTaskProgressNotFoundReturns404() throws Exception {
        mockMvc.perform(get("/api/tasks/no-such-task"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("任务不存在"));
    }
}
