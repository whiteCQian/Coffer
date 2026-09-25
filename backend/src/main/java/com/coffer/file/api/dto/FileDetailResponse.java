package com.coffer.file.api.dto;

import com.coffer.tag.api.dto.FileTagInfo;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件详情响应 DTO：文件基础信息 + 已确认/待确认标签列表 + 标签汇总状态 + 临时预览 URL。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileDetailResponse {

    /** 文件 ID。 */
    private Long id;

    /** 文件名称。 */
    private String fileName;

    /** 文件类型（扩展名，不含点号）。 */
    private String fileType;

    /** 文件大小（字节）。 */
    private Long fileSize;

    /** 上传时间。 */
    private LocalDateTime uploadTime;

    /** AI 摘要。 */
    private String summary;

    /** 处理状态（PENDING/PROCESSING/COMPLETED/FAILED）。 */
    @Schema(description = "处理状态", allowableValues = {"PENDING", "PROCESSING", "COMPLETED", "FAILED"})
    private String status;

    /** 标签汇总确认状态（ALL_CONFIRMED/PENDING/ALL_REJECTED/NO_TAG）。 */
    @Schema(description = "标签汇总确认状态", allowableValues = {"ALL_CONFIRMED", "PENDING", "ALL_REJECTED", "NO_TAG"})
    private String tagStatus;

    /** 已确认（CONFIRMED）标签列表。 */
    private List<FileTagInfo> confirmedTags;

    /** 待确认（PENDING_CONFIRMATION）标签列表。 */
    private List<FileTagInfo> pendingTags;

    /** 临时预览 URL（预签名，默认 7 天有效），供前端直接预览文件内容。 */
    private String previewUrl;

    /** 文件分类（受控词表，CONTRACT/INVOICE/REPORT/ID/IMAGE/VIDEO/OTHER）。 */
    @Schema(description = "文件分类", allowableValues = {"CONTRACT", "INVOICE", "REPORT", "ID", "IMAGE", "VIDEO", "OTHER"})
    private String category;

    /** 是否已归档（物理移动到分类目录）。 */
    private Boolean archived;

    /** 对象存储路径（MinIO 对象键），归档后指向分类目录。 */
    private String storagePath;
}
