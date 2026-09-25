package com.coffer.tool;

import com.coffer.tag.domain.ConfirmationStatus;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.tag.domain.FileTagMapping;
import com.coffer.tag.domain.Tag;
import com.coffer.config.HybridSearchProperties;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.tag.infrastructure.persistence.TagRepository;
import com.coffer.service.ChatCitationCollector;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文件搜索工具：供 LLM 调用，按关键词搜索文件。
 *
 * <p>搜索策略（无向量库时的兜底方案，保证项目初期稳定运行）：
 * <ol>
 *   <li>MySQL 全文索引 {@code MATCH(file_name, summary) AGAINST(...)} 按相关性检索；</li>
 *   <li>全文索引未就绪（H2 测试环境 / 未执行 DDL）时捕获异常降级为 LIKE 文件名模糊匹配；</li>
 *   <li>标签维度经 JOIN 三表查询已确认标签名含关键词的文件；</li>
 *   <li>全文/文件名匹配优先，标签匹配随后，合并去重后最多返回 10 条。</li>
 * </ol>
 * 结果以摘要文本（文件名/类型/已确认标签/摘要）形式返回。
 *
 * <p>同一实例另提供 {@code get_recent_uploads} 工具（最近上传 = 最近 8 个文件，
 * 先进先出队列），供模型回答“最近/最新/刚刚上传”类问题时取真实数据，
 * 与 {@code search_files}（按关键词找指定文件）是两种不同查询，语义不得混用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileSearchTool {

    /** 单次搜索最多返回的文件数，避免返回信息过多。 */
    private static final long MAX_RESULT_SIZE = 10;

    /** 「最近上传」队列长度：先进先出窗口，最多保留最近 8 个上传文件。 */
    private static final int RECENT_UPLOADS_LIMIT = 8;

    /** 返回给模型的摘要预览上限（字符），避免单条输出过长占用上下文。 */
    private static final int SUMMARY_PREVIEW_MAX = 120;

    /** 上传时间展示格式，与文件列表/详情保持一致。 */
    private static final DateTimeFormatter UPLOAD_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FileMetadataRepository fileMetadataRepository;
    private final FileTagMappingRepository fileTagMappingRepository;
    private final TagRepository tagRepository;
    private final HybridSearchProperties hybridSearchProperties;
    private final com.coffer.service.HybridSearchService hybridSearchService;
    private final ChatCitationCollector citationCollector;

    /**
     * 根据关键词搜索文件，返回匹配文件列表的摘要信息。
     *
     * @param queryKeyword 用户输入的关键词或查询语句，用于匹配文件名和标签，支持模糊搜索
     * @return 匹配文件的摘要文本（按行分隔，最多 10 条）；无结果或异常时返回提示信息
     */
    @Tool(name = "search_files",
            value = "当用户使用自然语言描述查询需求时调用此工具，支持通过关键词匹配文件名和标签来查找文件，适用于模糊搜索、忘记文件名的场景和对话中需要检索文件时使用，输入关键词返回匹配的文件摘要列表，协助 Agent 在对话中回答关于文件存在性和内容的问题")
    public String searchFiles(@P("用户输入的查询关键词或自然语言描述中的核心词汇，系统将使用该关键词对文件名和标签进行模糊匹配查找相关文件") String queryKeyword) {
        if (queryKeyword == null || queryKeyword.isBlank()) {
            return "搜索关键词不能为空";
        }
        try {
            String keyword = queryKeyword.trim();
            if (hybridSearchProperties.isEnabled()) {
                List<com.coffer.service.HybridSearchService.SearchEvidence> fusedMatches =
                        hybridSearchService.searchWithEvidence(keyword);
                if (fusedMatches.isEmpty()) {
                    return "未找到与关键词 '" + queryKeyword + "' 匹配的文件";
                }
                return fusedMatches.stream()
                        .limit(MAX_RESULT_SIZE)
                        .peek(hit -> captureCitation(hit.file(), hit.score(), hit.retrievalType()))
                        .map(hit -> formatSearchEntry(hit.file()))
                        .collect(Collectors.joining("\n"));
            }
            // 1) 文件名/摘要全文匹配：优先走 MySQL FULLTEXT（MATCH...AGAINST 按相关性排序，含 Qwen-VL
            //    图片描述等 summary 内容）；全文索引未就绪或语法不兼容（如 H2 测试环境）时捕获异常。
            //    全文不可用或未命中（如单字/过短分词）时，再按 文件名+摘要 LIKE 补一次召回，保证降级路径
            //    也能按内容检索到图片等文件——这是让对话模型经搜索工具感知图片概要的关键兜底。
            List<FileMetadata> nameMatches;
            boolean fullTextAvailable = true;
            try {
                nameMatches = fileMetadataRepository.fullTextSearch(keyword);
            } catch (Exception e) {
                log.warn("全文索引不可用，降级 LIKE 模糊匹配 keyword={}: {}", keyword, e.getMessage());
                fullTextAvailable = false;
                nameMatches = List.of();
            }
            if (!fullTextAvailable || nameMatches.isEmpty()) {
                nameMatches = fileMetadataRepository.findByFileNameOrSummaryContainingIgnoreCase(keyword);
            }
            // 2) 标签匹配：仅已确认（CONFIRMED）标签参与匹配；先查 fileId 列表再批量装载实体，
            //    未确认/已拒绝的标签不会命中
            List<Long> tagFileIds = fileTagMappingRepository
                    .findFileIdsByTagNameAndStatus(keyword, ConfirmationStatus.CONFIRMED);
            List<FileMetadata> tagMatches = tagFileIds.isEmpty()
                    ? List.of()
                    : fileMetadataRepository.findAllById(tagFileIds);

            // 3) 去重合并，全文/文件名匹配在前（相关性高）、标签匹配在后（LinkedHashSet 保持插入顺序）
            Set<FileMetadata> merged = new LinkedHashSet<>();
            merged.addAll(nameMatches);
            merged.addAll(tagMatches);

            if (merged.isEmpty()) {
                return "未找到与关键词 '" + queryKeyword + "' 匹配的文件";
            }
            // 4) 最多返回 10 条，构建摘要信息并换行拼接
            List<FileMetadata> resultFiles = merged.stream()
                    .limit(MAX_RESULT_SIZE)
                    .toList();
            for (int i = 0; i < resultFiles.size(); i++) {
                captureCitation(resultFiles.get(i), 1.0d / (i + 1), "KEYWORD");
            }
            return resultFiles.stream()
                    .map(this::formatSearchEntry)
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.error("文件搜索失败 keyword={}: {}", queryKeyword, e.getMessage(), e);
            return "搜索失败，请稍后重试";
        }
    }

    /**
     * 获取「最近上传」的真实文件列表：按上传时间倒序的最近 8 个文件（先进先出队列），
     * 其中第一条即最近一次上传，作为回答“最近/最新/刚刚上传”类问题的唯一事实依据。
     *
     * <p>队列由数据库按上传时间实时推导（{@link FileMetadataRepository#findRecentFiles}），
     * 新上传自动顶替最旧文件、删除即时生效，无需单独存储队列。
     *
     * @return 最近 8 个上传文件的摘要文本（换行分隔，每条含文件ID/文件名/类型/大小/上传时间/状态/已确认标签/AI摘要）；
     *         尚无文件或查询异常时返回对应提示，不抛异常
     */
    @Tool(name = "get_recent_uploads",
            value = "当用户询问“最近上传/最新上传/刚刚上传”了哪些文件、想知道哪个文件是最近（最新）上传的、"
                    + "或要求读取/分析刚上传的文件时调用。返回系统内按上传时间倒序的最近 8 个文件"
                    + "（含文件ID、文件名、类型、大小、上传时间、处理状态、已确认标签、AI 摘要），其中第一条即最近一次上传。"
                    + "回答“最近上传”类问题必须依据本工具的真实返回作答，不得猜测或编造；"
                    + "若要读取最新文件的内容，请用返回的“文件ID”调用 parse_file")
    public String getRecentUploads() {
        try {
            List<FileMetadata> recent = fileMetadataRepository
                    .findRecentFiles(PageRequest.of(0, RECENT_UPLOADS_LIMIT));
            if (recent.isEmpty()) {
                return "当前还没有上传任何文件";
            }
            for (int i = 0; i < recent.size(); i++) {
                captureCitation(recent.get(i), 1.0d / (i + 1), "RECENT_UPLOAD");
            }
            return recent.stream()
                    .map(this::formatRecentEntry)
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.error("查询最近上传文件失败: {}", e.getMessage(), e);
            return "查询最近上传失败，请稍后重试";
        }
    }

    /**
     * 将单个文件格式化为「最近上传」返回行（含文件ID 便于模型接 parse_file 读取内容）。
     */
    private String formatRecentEntry(FileMetadata file) {
        return "文件ID：" + file.getId()
                + "，文件名：" + nullSafe(file.getFileName(), "未命名")
                + "，类型：" + nullSafe(file.getFileType(), "未知")
                + "，大小：" + (file.getFileSize() == null ? "0" : file.getFileSize()) + " 字节"
                + "，上传时间：" + (file.getUploadTime() == null ? "未知" : UPLOAD_TIME_FORMAT.format(file.getUploadTime()))
                + "，状态：" + statusLabel(file.getStatus())
                + "，标签：" + getTagsByFileId(file.getId())
                + "，摘要：" + previewSummary(file.getSummary());
    }

    /** 构建搜索工具返回给模型的单条文件摘要。 */
    private String formatSearchEntry(FileMetadata file) {
        return "文件名：" + file.getFileName()
                + "，类型：" + nullSafe(file.getFileType(), "未知")
                + "，标签：" + getTagsByFileId(file.getId())
                + "，摘要：" + nullSafe(file.getSummary(), "暂无");
    }

    /** 将真实检索命中记录写入本轮对话引用。 */
    private void captureCitation(FileMetadata file, Double score, String retrievalType) {
        citationCollector.capture(file, score, retrievalType, previewSummary(file.getSummary()));
    }

    /**
     * 将文件处理状态映射为中文展示文案。
     */
    private String statusLabel(FileStatus status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case COMPLETED -> "已完成";
            case PROCESSING -> "解析中";
            case FAILED -> "解析失败";
            case PENDING -> "排队待解析";
        };
    }

    /**
     * 摘要预览：压平换行并截断到 {@link #SUMMARY_PREVIEW_MAX} 字符。
     */
    private String previewSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return "暂无";
        }
        String flat = summary.replace('\n', ' ').replace('\r', ' ').trim();
        if (flat.length() <= SUMMARY_PREVIEW_MAX) {
            return flat;
        }
        return flat.substring(0, SUMMARY_PREVIEW_MAX) + "…";
    }

    /**
     * 批量解析标签关联对应的标签名，构建 {@code tagId -> tagName} 映射。
     */
    private Map<Long, String> resolveTagNames(List<FileTagMapping> mappings) {
        List<Long> tagIds = mappings.stream().map(FileTagMapping::getTagId).distinct().toList();
        if (tagIds.isEmpty()) {
            return Map.of();
        }
        return tagRepository.findAllById(tagIds).stream()
                .collect(Collectors.toMap(Tag::getId, Tag::getTagName));
    }

    /**
     * 获取指定文件已确认（CONFIRMED）的标签列表，拼接为逗号分隔字符串。
     *
     * @param fileId 文件 ID
     * @return 已确认标签的逗号分隔字符串；无标签时返回「暂无」
     */
    private String getTagsByFileId(Long fileId) {
        List<FileTagMapping> mappings = fileTagMappingRepository
                .findByFileIdAndConfirmationStatus(fileId, ConfirmationStatus.CONFIRMED);
        if (mappings.isEmpty()) {
            return "暂无";
        }
        Map<Long, String> nameById = resolveTagNames(mappings);
        return mappings.stream()
                .map(mapping -> nameById.get(mapping.getTagId()))
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.joining(","));
    }

    /**
     * 空值/空白兜底。
     */
    private String nullSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
