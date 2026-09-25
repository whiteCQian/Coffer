package com.coffer.tag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件详情中的单个标签信息，供前端针对每个标签执行确认/拒绝操作。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileTagInfo {

    /** 标签 ID。 */
    private Long tagId;

    /** 标签名。 */
    private String tagName;

    /** 确认状态（PENDING_CONFIRMATION/CONFIRMED/REJECTED）。 */
    @Schema(description = "标签确认状态", allowableValues = {"PENDING_CONFIRMATION", "CONFIRMED", "REJECTED"})
    private String confirmationStatus;
}
