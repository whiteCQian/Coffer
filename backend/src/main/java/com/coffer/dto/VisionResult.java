package com.coffer.dto;

import com.coffer.file.domain.CategoryType;

import java.util.List;

/**
 * 视觉模型识别结果：单一受控分类 + 关键词标签列表 + 一句话描述。
 *
 * <p>由 {@code VisionModelService.describeImage} 产出，供上传管道图片分支落库：
 * {@code category} 写入 {@code FileMetadata.category}（决定归档目录 slug），
 * {@code tags} 复用现有标签入库链路（Tag + FileTagMapping，PENDING_CONFIRMATION），
 * {@code description} 作为摘要写入 {@code FileMetadata.summary}。
 *
 * <p>解析失败时降级为 {@code category=OTHER + 空 tags + 空 description}，永不抛异常。
 */
public record VisionResult(CategoryType category, List<String> tags, String description) {
}
