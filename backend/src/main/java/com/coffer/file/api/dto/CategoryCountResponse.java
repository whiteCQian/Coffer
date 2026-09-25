package com.coffer.file.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分类计数响应 DTO：某个受控分类下的文件数量（供全部文件页分类徽标等展示）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryCountResponse {

    /** 分类枚举名（CONTRACT/INVOICE/REPORT/ID/IMAGE/VIDEO/OTHER）。 */
    @Schema(description = "文件分类", allowableValues = {"CONTRACT", "INVOICE", "REPORT", "ID", "IMAGE", "VIDEO", "OTHER"})
    private String category;

    /** 该分类下的文件数量。 */
    private Long count;
}
