package com.coffer.task.application;

import com.coffer.task.api.dto.FailedTaskItem;
import com.coffer.tag.api.dto.PendingConfirmItem;
import com.coffer.task.api.dto.ProcessingTaskItem;
import com.coffer.task.api.dto.TaskOverviewResponse;
import com.coffer.task.domain.AsyncTask;
import com.coffer.task.domain.AsyncTaskStatus;
import com.coffer.file.domain.FileMetadata;
import com.coffer.task.infrastructure.persistence.AsyncTaskRepository;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 首页总览服务：聚合「处理中任务 / 失败任务 / 待确认标签文件」三类面板数据。
 *
 * <p>处理中与失败取自异步任务表（按创建时间倒序），待确认文件取自
 * 「已处理完成且仍有待确认标签」的文件（见 {@link FileMetadataRepository#findPendingConfirmFiles()}）。
 */
@Slf4j
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class TaskOverviewService {

    /** 每组返回明细条数封顶值：仅限制返回的明细项，计数仍为数据库中的真实总数。 */
    private static final int MAX_ITEMS = 50;

    private final AsyncTaskRepository asyncTaskRepository;
    private final FileMetadataRepository fileMetadataRepository;

    /**
     * 首页总览聚合：组装三组计数与明细并返回。
     *
     * <p>Repository 各返回完整列表（规模可控），服务端在流中 {@code limit(MAX_ITEMS)}
     * 截断明细防抖；计数取 {@code size()} 保留真实值。
     *
     * @return 总览响应（计数 + 各面板明细）
     */
    public TaskOverviewResponse getOverview() {
        List<AsyncTask> processingTasks =
                asyncTaskRepository.findByStatusOrderByCreatedAtDesc(AsyncTaskStatus.PROCESSING);
        List<AsyncTask> failedTasks =
                asyncTaskRepository.findByStatusOrderByCreatedAtDesc(AsyncTaskStatus.FAILED);
        List<FileMetadata> pendingConfirmFiles = fileMetadataRepository.findPendingConfirmFiles();

        List<ProcessingTaskItem> processing = processingTasks.stream()
                .limit(MAX_ITEMS)
                .map(t -> ProcessingTaskItem.builder()
                        .taskId(t.getTaskId())
                        .fileName(t.getFileName())
                        .runMode(t.getRunMode())
                        .progress(t.getProgress())
                        .updatedAt(t.getUpdatedAt())
                        .build())
                .toList();
        List<FailedTaskItem> failed = failedTasks.stream()
                .limit(MAX_ITEMS)
                .map(t -> FailedTaskItem.builder()
                        .fileId(resolveFileId(t))
                        .taskId(t.getTaskId())
                        .fileName(t.getFileName())
                        .runMode(t.getRunMode())
                        // 任务结果字段在失败时承载错误信息
                        .error(t.getResult())
                        .updatedAt(t.getUpdatedAt())
                        .build())
                .toList();
        List<PendingConfirmItem> pendingConfirm = pendingConfirmFiles.stream()
                .limit(MAX_ITEMS)
                .map(f -> PendingConfirmItem.builder()
                        .fileId(f.getId())
                        .fileName(f.getFileName())
                        .uploadTime(f.getUploadTime())
                        .build())
                .toList();

        return TaskOverviewResponse.builder()
                .processingCount(processingTasks.size())
                .failedCount(failedTasks.size())
                .pendingConfirmCount(pendingConfirmFiles.size())
                .processing(processing)
                .failed(failed)
                .pendingConfirm(pendingConfirm)
                .build();
    }

    /**
     * 由任务 ID 解析对应文件 ID；文件已删除（如清理孤儿任务）时返回 null，
     * 前端据此隐藏「重试」按钮避免请求不存在的文件。
     *
     * @param task 失败任务
     * @return 文件 ID，可能为 null
     */
    private Long resolveFileId(AsyncTask task) {
        FileMetadata fm = fileMetadataRepository.findByTaskId(task.getTaskId()).orElse(null);
        return fm == null ? null : fm.getId();
    }
}
