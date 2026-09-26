package com.coffer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话回复引用的真实文件来源。
 *
 * <p>该对象只由文件检索/解析工具根据数据库中的文件记录构造，
 * 不从模型生成的文本中反向提取，避免把模型臆测的文件当成引用来源。</p>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ChatCitation {

    /** 可用于打开文件详情和后续定位原文件的数据库 ID。 */
    private Long fileId;
    private Long revision;
    private String contentUrl;

    /** 文件名。 */
    private String fileName;

    /** 文件类型（不含点号）。 */
    private String fileType;

    /** 当前第一版使用文件摘要作为文件级命中片段。 */
    private String snippet;

    /** 检索相关性分数；不同检索类型的分数只用于当前结果内排序，不跨查询比较。 */
    private Double score;

    /** 检索来源类型，例如 KEYWORD、VECTOR、HYBRID、RECENT_UPLOAD。 */
    private String retrievalType;
}
