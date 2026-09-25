package com.coffer.tag.api.dto;

import com.coffer.file.domain.CategoryType;

import java.util.List;

/**
 * LLM 打标结构化结果：单一受控分类 + 关键词标签列表。
 *
 * <p>由 {@code TagGenerationTool.generateTagAndCategory} 产出，供上传管道落库：
 * {@code category} 写入 {@code FileMetadata.category}（决定归档目录 slug），
 * {@code tags} 复用现有标签入库链路（Tag + FileTagMapping，PENDING_CONFIRMATION）。
 */
public record TagAndCategoryResult(CategoryType category, List<String> tags) {
}
