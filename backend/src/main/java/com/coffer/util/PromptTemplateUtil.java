package com.coffer.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 提示词模板工具：动态拼接用户上下文信息，增强 Agent 的理解与指代消解能力。
 *
 * <p>各模板以常量作为内置兜底，可通过 {@code coffer.prompt.*} 配置覆盖；
 * 所有入参均做空值兜底，保证生成的提示词结构完整、始终有效。
 */
@Component
public class PromptTemplateUtil {

    /** 文件上下文提示词默认模板。 */
    public static final String DEFAULT_FILE_CONTEXT_TEMPLATE =
            "当前正在处理的文件信息：文件名为 {fileName}，类型为 {fileType}，大小为 {fileSize} 字节，上传时间为 {uploadTime}";
    /** 搜索上下文提示词默认模板。 */
    public static final String DEFAULT_SEARCH_CONTEXT_TEMPLATE =
            "用户查询：{query}\n最近对话历史：\n{recentHistory}";
    /** 标签生成提示词默认模板。 */
    public static final String DEFAULT_TAG_GENERATION_TEMPLATE =
            "请根据以下文件内容生成 3 到 5 个关键词标签，文件名为 {fileName}，类型为 {fileType}，内容预览如下：\n{contentPreview}";
    /** 系统提示词兜底。 */
    public static final String DEFAULT_SYSTEM_PROMPT =
            "你是 Coffer 智能文件管家，擅长总结与分类，能够准确理解用户的文件管理需求并调用合适的工具完成任务";

    /** 空值兜底文案。 */
    private static final String FALLBACK_FILE_NAME = "未命名文件";
    private static final String FALLBACK_FILE_TYPE = "未知类型";
    private static final String FALLBACK_UPLOAD_TIME = "未知时间";
    private static final String FALLBACK_QUERY = "（空查询）";
    private static final String FALLBACK_HISTORY = "（无历史对话）";
    private static final String FALLBACK_CONTENT_PREVIEW = "（无内容预览）";

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 匹配 {@code {key}} 形式的占位符。 */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([^{}]+)}");

    /** 文件上下文模板，可在 application.yml 的 {@code coffer.prompt.file-context} 覆盖。 */
    @Value("${coffer.prompt.file-context:}")
    private String fileContextTemplate;

    /** 搜索上下文模板，可在 application.yml 的 {@code coffer.prompt.search-context} 覆盖。 */
    @Value("${coffer.prompt.search-context:}")
    private String searchContextTemplate;

    /** 标签生成模板，可在 application.yml 的 {@code coffer.prompt.tag-generation} 覆盖。 */
    @Value("${coffer.prompt.tag-generation:}")
    private String tagGenerationTemplate;

    /** 基础系统提示词，与 {@code AiAgentConfig} 共用 {@code coffer.system-prompt}。 */
    @Value("${coffer.system-prompt:}")
    private String systemPrompt;

    /**
     * 构建文件解析场景的上下文提示词。
     *
     * @param fileName   文件名
     * @param fileType   文件类型（扩展名）
     * @param fileSize   文件大小（字节）
     * @param uploadTime 上传时间
     * @return 拼接完成的提示词
     */
    public String buildFileContextPrompt(String fileName, String fileType, Long fileSize, LocalDateTime uploadTime) {
        Map<String, String> values = Map.of(
                "fileName", defaultIfBlank(fileName, FALLBACK_FILE_NAME),
                "fileType", defaultIfBlank(fileType, FALLBACK_FILE_TYPE),
                "fileSize", String.valueOf(fileSize == null ? 0L : fileSize),
                "uploadTime", uploadTime == null ? FALLBACK_UPLOAD_TIME : DATE_TIME_FORMATTER.format(uploadTime));
        return replacePlaceholders(resolveTemplate(fileContextTemplate, DEFAULT_FILE_CONTEXT_TEMPLATE), values);
    }

    /**
     * 构建搜索场景的上下文提示词，携带最近对话历史用于指代消解。
     *
     * @param query         用户当前搜索查询
     * @param recentHistory 最近几条对话历史（每条一轮），可为空
     * @return 拼接完成的提示词
     */
    public String buildSearchContextPrompt(String query, List<String> recentHistory) {
        String historyText = (recentHistory == null || recentHistory.isEmpty())
                ? FALLBACK_HISTORY
                : recentHistory.stream()
                        .filter(StringUtils::hasText)
                        .collect(Collectors.joining("\n"));
        if (!StringUtils.hasText(historyText)) {
            historyText = FALLBACK_HISTORY;
        }
        Map<String, String> values = Map.of(
                "query", defaultIfBlank(query, FALLBACK_QUERY),
                "recentHistory", historyText);
        return replacePlaceholders(resolveTemplate(searchContextTemplate, DEFAULT_SEARCH_CONTEXT_TEMPLATE), values);
    }

    /**
     * 构建标签生成场景的提示词。
     *
     * @param fileName       文件名
     * @param fileType       文件类型（扩展名）
     * @param contentPreview 文件内容预览，可为空
     * @return 拼接完成的提示词
     */
    public String buildTagGenerationPrompt(String fileName, String fileType, String contentPreview) {
        Map<String, String> values = Map.of(
                "fileName", defaultIfBlank(fileName, FALLBACK_FILE_NAME),
                "fileType", defaultIfBlank(fileType, FALLBACK_FILE_TYPE),
                "contentPreview", defaultIfBlank(contentPreview, FALLBACK_CONTENT_PREVIEW));
        return replacePlaceholders(resolveTemplate(tagGenerationTemplate, DEFAULT_TAG_GENERATION_TEMPLATE), values);
    }

    /**
     * 将动态上下文按 {@code {key}} 占位符合并进基础系统提示词。
     *
     * @param baseSystemPrompt 基础系统提示词，为空时回退到配置或默认值
     * @param context          动态上下文参数（key 对应模板占位符），可为空
     * @return 合并后的完整系统提示词
     */
    public String buildSystemPromptWithContext(String baseSystemPrompt, Map<String, String> context) {
        String base = defaultIfBlank(baseSystemPrompt, resolveTemplate(systemPrompt, DEFAULT_SYSTEM_PROMPT));
        if (context == null || context.isEmpty()) {
            return base;
        }
        return replacePlaceholders(base, context);
    }

    /**
     * 选择生效模板：配置覆盖优先，否则回退到内置常量。
     */
    private String resolveTemplate(String configured, String defaultTemplate) {
        return StringUtils.hasText(configured) ? configured : defaultTemplate;
    }

    /**
     * 空值/空白兜底。
     */
    private String defaultIfBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    /**
     * 将模板中的 {@code {key}} 占位符替换为上下文值；缺失或空值替换为空串。
     */
    private String replacePlaceholders(String template, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder(template.length());
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
