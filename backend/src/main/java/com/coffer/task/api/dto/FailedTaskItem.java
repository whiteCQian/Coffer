package com.coffer.task.api.dto;

import com.coffer.governance.domain.GovernanceRunMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 首页总览「失败」面板明细项。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailedTaskItem {

    /** 文件 ID，供前端失败重试（POST /files/{id}/retry）。 */
    private Long fileId;

    /** 任务 ID。 */
    private String taskId;

    /** 失败任务对应的文件名。 */
    private String fileName;

    /** Runtime mode captured when the task was registered. */
    private GovernanceRunMode runMode;

    /** 失败原因（取自任务结果字段，失败时为错误信息）。 */
    private String error;

    /** 最近更新时间。 */
    private LocalDateTime updatedAt;
}
