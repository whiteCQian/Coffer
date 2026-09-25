package com.coffer.task.api.dto;

import com.coffer.governance.domain.GovernanceRunMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 首页总览「处理中」面板明细项。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessingTaskItem {

    /** 任务 ID。 */
    private String taskId;

    /** 正在处理的文件名。 */
    private String fileName;

    /** Runtime mode captured when the task was registered. */
    private GovernanceRunMode runMode;

    /** 进度百分比，取值 0 到 100。 */
    private Integer progress;

    /** 最近更新时间。 */
    private LocalDateTime updatedAt;
}
