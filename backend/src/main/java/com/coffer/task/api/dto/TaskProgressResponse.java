package com.coffer.task.api.dto;

import com.coffer.governance.domain.GovernanceRunMode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 任务进度查询响应 DTO。
 *
 * <p>异步任务状态快照，供前端轮询上传后文件的处理进度。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskProgressResponse {
    private String modelSnapshotId;

    /** 任务 ID。 */
    private String taskId;

    /** 处理的文件名。 */
    private String fileName;

    /** Runtime mode captured when the task started. */
    private GovernanceRunMode runMode;

    /** 任务状态（枚举值转字符串：PENDING/PROCESSING/COMPLETED/FAILED）。 */
    @Schema(description = "任务状态", allowableValues = {"PENDING", "PROCESSING", "COMPLETED", "FAILED"})
    private String status;

    /** 进度百分比，0 到 100。 */
    private Integer progress;

    /** 处理结果：成功时为摘要，失败时为错误信息。 */
    private String result;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;
}
