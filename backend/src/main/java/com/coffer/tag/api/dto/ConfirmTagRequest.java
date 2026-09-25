package com.coffer.tag.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 确认文件标签关联请求体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmTagRequest {

    /** 文件 ID。 */
    @NotNull(message = "文件 ID 不能为空")
    private Long fileId;

    /** 标签 ID。 */
    @NotNull(message = "标签 ID 不能为空")
    private Long tagId;
}
