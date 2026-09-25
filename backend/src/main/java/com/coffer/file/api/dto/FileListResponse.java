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
 * 文件列表查询响应 DTO。
 *
 * <p>在文件基础字段之外补充卡片渲染所需的展示字段：{@code tagStatus}
 * 汇总确认状态、{@code tags} 为「非拒绝」标签明细、{@code category/status/archived}
 * 为文件元数据的直接映射——均沿用列表装配点的批量装载，不为每条文件增加额外查询。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileListResponse {

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

    /** 文件分类（受控枚举名，CONTRACT/INVOICE/REPORT/ID/IMAGE/VIDEO/OTHER）。 */
    @Schema(description = "文件分类", allowableValues = {"CONTRACT", "INVOICE", "REPORT", "ID", "IMAGE", "VIDEO", "OTHER"})
    private String category;

    /** 处理状态（PENDING/PROCESSING/COMPLETED/FAILED）。 */
    @Schema(description = "处理状态", allowableValues = {"PENDING", "PROCESSING", "COMPLETED", "FAILED"})
    private String status;

    /** 是否已归档（物理移动到分类目录）。 */
    private boolean archived;

    /**
     * 标签汇总确认状态，取值为：
     * <ul>
     *   <li>{@code ALL_CONFIRMED} 所有标签均已确认</li>
     *   <li>{@code PENDING} 存在至少一个待确认标签（优先级最高）</li>
     *   <li>{@code ALL_REJECTED} 所有标签均被拒绝</li>
     *   <li>{@code NO_TAG} 尚未生成任何标签</li>
     * </ul>
     * 混合状态（既有已确认又有已拒绝、无待确认）按简化规则统一返回 {@code PENDING}。
     */
    @Schema(description = "标签汇总确认状态", allowableValues = {"ALL_CONFIRMED", "PENDING", "ALL_REJECTED", "NO_TAG"})
    private String tagStatus;

    /**
     * 非拒绝标签明细（CONFIRMED + PENDING_CONFIRMATION），供卡片直接渲染标签 chips，
     * 无需逐条发起详情查询。已拒绝的标签视为作废不返回。
     */
    private List<FileTagInfo> tags;

}
