package com.coffer.tool;

import com.coffer.annotation.LogModelCall;
import com.coffer.model.provider.ChatProvider;
import com.coffer.tag.api.dto.TagAndCategoryResult;
import com.coffer.file.domain.CategoryType;
import com.coffer.util.TextTruncator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 标签生成工具：供 LLM 调用，截断文本后调用模型生成关键词标签。
 *
 * <p>两条路径，语义互不干扰：
 * <ul>
 *   <li>{@code generate_tags}（{@link #generateTags}）：对话 Agent 的 {@code @Tool}，
 *       仍返回中文逗号分隔的标签列表，不改动既有 Agent 语义。</li>
 *   <li>{@link #generateTagAndCategory}（非 {@code @Tool}）：上传管道使用，
 *       一次调用同时产出受控分类（category，受 {@link CategoryType} 词表约束）与标签列表，
 *       返回结构化 {@link TagAndCategoryResult}。</li>
 * </ul>
 * 两条路径共用 {@link #callModel} 完成「截断 → 调用 → 取回复」的模型调用逻辑。
 */
@Slf4j
@Component
@com.coffer.auth.service.OwnerOnly
@RequiredArgsConstructor
public class TagGenerationTool {

    /** 标签生成的系统提示词，约束模型只输出逗号分隔的标签列表。 */
    private static final String SYSTEM_PROMPT =
            "你是一个专业的标签生成助手，请根据提供的文本内容生成 3 到 5 个关键词标签，每个标签用逗号分隔，不要有多余的解释和标点符号，只输出标签列表";

    /** 分类 + 标签生成的系统提示词模板，要求输出受控分类 JSON，词表经 {@code {categoryLabels}} 注入。 */
    private static final String TAG_AND_CATEGORY_PROMPT_TEMPLATE =
            "你是一个专业的文件分类与标签生成助手。请根据提供的文件内容，判断文件所属分类，并生成 3 到 5 个关键词标签。\n"
                    + "分类只能取以下受控值之一（用中文）：{categoryLabels}。判断务必保守："
                    + "只有当文件内容与某一分类高度吻合、证据充分时才选择该具体分类。\n"
                    + "「证件」仅限身份证、护照、驾驶证、营业执照等法定证照的扫描件或照片；"
                    + "普通资料、截图、非证照文档一律不属于证件。\n"
                    + "凡内容不属于任何具体分类、或难以可靠判断的情形，一律取「其他」，不要强行归类。\n"
                    + "请只输出一个 JSON 对象，格式为 {\"category\":\"分类\",\"tags\":[\"标签1\",\"标签2\"]}，"
                    + "不要输出任何多余解释或 Markdown 代码块。";

    private final ChatProvider chatProvider;
    private final TextTruncator textTruncator;
    private final ObjectMapper objectMapper;

    /** Actual model identifier selected by the active runtime mode. */
    public String modelName() {
        return chatProvider.modelName();
    }

    /**
     * 根据文件文本内容自动生成 3 到 5 个关键词标签（对话 Agent 工具路径）。
     *
     * @param textContent 需要生成标签的文本内容，从文件中提取的纯文本
     * @return 中文逗号分隔的标签列表；文本为空或调用失败时返回错误信息字符串
     */
    @LogModelCall
    @Tool(name = "generate_tags",
            value = "根据文件文本内容自动生成 3 到 5 个关键词标签，调用时机为文件解析完成后需要对文件进行分类和检索标签生成时使用，输入纯文本内容返回逗号分隔的标签列表，适用于文件自动分类、关键词提取和智能检索场景")
    public String generateTags(@P("从文件中提取的纯文本内容，由 parse_file 工具或文档解析服务获取，用于模型分析并生成关键词标签") String textContent) {
        if (textContent == null || textContent.isBlank()) {
            return "文本内容为空，无法生成标签";
        }
        try {
            String reply = callModel(SYSTEM_PROMPT, textContent);
            if (reply == null || reply.isBlank()) {
                return "标签生成失败，请稍后重试";
            }
            // 兼容英文逗号与中文全角逗号，去除首尾空格并过滤空项
            List<String> tags = List.of(reply.split("[,，]"))
                    .stream()
                    .map(String::trim)
                    .filter(tag -> !tag.isEmpty())
                    .toList();
            return tags.isEmpty() ? "标签生成失败，请稍后重试" : String.join("，", tags);
        } catch (Exception e) {
            log.error("标签生成失败，异常类型={}", e.getClass().getSimpleName());
            return "标签生成失败，请稍后重试";
        }
    }

    /**
     * 根据文件文本内容生成受控分类 + 关键词标签（上传管道路径，非 {@code @Tool}）。
     *
     * <p>一次模型调用同时产出单一分类（受 {@link CategoryType} 词表约束）与标签列表，
     * 避免两次调用增加成本。解析失败时降级：category 返回 {@code OTHER}，tags 尽量按逗号兜底提取。
     *
     * @param textContent 需要分析的文本内容
     * @return 结构化分类 + 标签结果（永不抛异常）
     */
    @LogModelCall
    public TagAndCategoryResult generateTagAndCategory(String textContent) {
        if (textContent == null || textContent.isBlank()) {
            return new TagAndCategoryResult(CategoryType.OTHER, List.of());
        }
        String categoryLabels = Arrays.stream(CategoryType.values())
                .map(CategoryType::getLabel)
                .collect(Collectors.joining("、"));
        String systemPrompt = TAG_AND_CATEGORY_PROMPT_TEMPLATE.replace("{categoryLabels}", categoryLabels);
        String reply = callModel(systemPrompt, textContent);
        return parseTagAndCategory(reply);
    }

    /**
     * 截断文本后调用模型并返回 AI 消息文本（两条生成路径共用）。
     *
     * @param systemPrompt 系统提示词
     * @param userText     用户文本（调用前截断防超上下文）
     * @return 模型回复文本，可能为空串
     */
    private String callModel(String systemPrompt, String userText) {
        // 截断超长文本，防止超出模型上下文窗口
        String truncated = textTruncator.truncateWithStats(userText).text();
        ChatResponse response = chatProvider.chat(
                SystemMessage.from(systemPrompt),
                UserMessage.from(truncated));
        return response.aiMessage() == null ? "" : response.aiMessage().text();
    }

    /**
     * 解析模型回复为 {@link TagAndCategoryResult}：剥离 Markdown 围栏 → JSON 解析 →
     * category 经 {@link CategoryType#fromLabel} 容错归一（未命中 OTHER）；
     * 任何解析失败均不抛异常，降级为 OTHER + 按逗号兜底提取标签。
     *
     * @param raw 模型原始回复
     * @return 结构化结果（永不抛异常）
     */
    private TagAndCategoryResult parseTagAndCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return new TagAndCategoryResult(CategoryType.OTHER, List.of());
        }
        try {
            String trimmed = stripCodeFence(raw);
            JsonNode root = objectMapper.readTree(trimmed);
            JsonNode categoryNode = root.path("category");
            CategoryType category = CategoryType.fromLabel(
                    categoryNode.isMissingNode() || categoryNode.isNull() ? null : categoryNode.asText());
            List<String> tags = new ArrayList<>();
            JsonNode tagsNode = root.path("tags");
            if (tagsNode.isArray()) {
                for (JsonNode node : tagsNode) {
                    String clean = (node.isTextual() ? node.asText() : node.toString()).trim();
                    if (!clean.isEmpty() && !tags.contains(clean)) {
                        tags.add(clean);
                    }
                }
            }
            return new TagAndCategoryResult(category, tags);
        } catch (Exception e) {
            log.warn("分类标签 JSON 解析失败，降级为 OTHER，异常类型={}", e.getClass().getSimpleName());
            return new TagAndCategoryResult(CategoryType.OTHER, extractFallbackTags(raw));
        }
    }

    /**
     * 剥离模型可能返回的 Markdown 代码块围栏（```json ... ```），仅处理以 ``` 开头的内容。
     *
     * @param raw 原始回复
     * @return 围栏内的内容；无围栏则原样返回
     */
    private String stripCodeFence(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline < 0) {
                return "";
            }
            trimmed = trimmed.substring(firstNewline + 1);
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence >= 0) {
                trimmed = trimmed.substring(0, lastFence);
            }
        }
        return trimmed.trim();
    }

    /**
     * JSON 解析失败的兜底：去除 JSON 结构字符与引号后按逗号切分提取标签。
     * 含冒号（如 "category":"合同" 残留）的片段视为键值对噪声过滤掉。
     *
     * @param raw 无法解析的原始回复
     * @return 尽力提取的标签列表（可为空）
     */
    private List<String> extractFallbackTags(String raw) {
        String cleaned = raw.replaceAll("[{}\\[\\]\"']", "");
        return Arrays.stream(cleaned.split("[,，]"))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty() && !tag.contains(":"))
                .distinct()
                .toList();
    }
}
