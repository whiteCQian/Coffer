package com.coffer.task.api.dto;

import com.coffer.tag.api.dto.PendingConfirmItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 首页总览响应：处理中 / 失败 / 待确认标签 三类面板的计数与明细。
 *
 * <p>三个明细列表各封顶 50 条（服务端常量控制），计数为真实总数、不受封顶影响。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskOverviewResponse {

    /** 处理中任务总数。 */
    private int processingCount;

    /** 失败任务总数。 */
    private int failedCount;

    /** 待确认标签文件总数。 */
    private int pendingConfirmCount;

    /** 处理中任务明细（最近 50 条）。 */
    private List<ProcessingTaskItem> processing;

    /** 失败任务明细（最近 50 条）。 */
    private List<FailedTaskItem> failed;

    /** 待确认标签文件明细（最近 50 条）。 */
    private List<PendingConfirmItem> pendingConfirm;
}
