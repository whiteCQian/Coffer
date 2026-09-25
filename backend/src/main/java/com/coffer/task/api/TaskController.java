package com.coffer.task.api;

import com.coffer.dto.Result;
import com.coffer.task.api.dto.TaskOverviewResponse;
import com.coffer.task.api.dto.TaskProgressResponse;
import com.coffer.task.application.TaskProgressApplicationService;
import com.coffer.task.application.TaskOverviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 异步任务进度查询接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskOverviewService taskOverviewService;
    private final TaskProgressApplicationService taskProgressApplicationService;

    /**
     * 首页总览：处理中任务、失败任务、待确认标签文件三类面板数据。
     *
     * <p>字面量路径 {@code /overview} 优先于 {@code /{taskId}} 匹配，不会被误判为任务查询。
     *
     * @return 统一响应，data 为三类面板的计数与明细
     */
    @GetMapping("/overview")
    public Result<TaskOverviewResponse> getOverview() {
        return Result.success(taskOverviewService.getOverview());
    }

    /**
     * 查询异步任务进度。
     *
     * @param taskId 任务 ID（上传接口返回）
     * @return 统一响应；任务存在时 data 为进度快照，不存在时 code=404
     */
    @GetMapping("/{taskId}")
    public Result<TaskProgressResponse> getTaskProgress(@PathVariable String taskId) {
        try {
            return Result.success(taskProgressApplicationService.getTaskProgress(taskId));
        } catch (IllegalArgumentException e) {
            log.warn("任务不存在 taskId={}", taskId);
            return Result.error(404, "任务不存在");
        }
    }
}
