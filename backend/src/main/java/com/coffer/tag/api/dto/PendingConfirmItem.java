package com.coffer.tag.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 首页总览「待确认标签」面板明细项。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingConfirmItem {

    /** 文件 ID，前端跳转详情/打标用。 */
    private Long fileId;

    /** 文件名。 */
    private String fileName;

    /** 上传时间。 */
    private LocalDateTime uploadTime;
}
