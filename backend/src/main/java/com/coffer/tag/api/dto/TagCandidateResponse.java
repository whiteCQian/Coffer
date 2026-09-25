package com.coffer.tag.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 标签候选响应 DTO：一个被 ≥1 个文件确认采用的标签名及覆盖文件数，
 * 供前端「建议标签 / 标签候选池」按热度展示。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TagCandidateResponse {

    /** 标签名（已确认关联的标签）。 */
    private String name;

    /** 确认采用该标签的去重文件数。 */
    private Long fileCount;
}
