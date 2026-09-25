package com.coffer.tag.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 拒绝文件标签关联请求体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RejectTagRequest {

    /** 文件 ID。 */
    @NotNull(message = "文件 ID 不能为空")
    private Long fileId;

    /** 待拒绝的标签 ID。 */
    @NotNull(message = "标签 ID 不能为空")
    private Long tagId;

    /** 修正标签名称（可选，可为 null 或空白；为空则仅拒绝）。 */
    private String newTagName;
}
