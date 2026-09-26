package com.coffer.service;

import com.coffer.annotation.LogModelCall;
import com.coffer.dto.VisionResult;
import com.coffer.file.domain.CategoryType;
import com.coffer.model.provider.VisionProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 多模态视觉识别服务：调用 Vision Provider 识别图片，
 * 产出「受控分类 category + 中文标签 tags + 一句话描述 description」。
 *
 * <p>供上传管道图片分支调用，语义与 {@code TagGenerationTool.generateTagAndCategory}
 * 对齐：一次模型调用同时产出分类与标签，避免两次调用增加成本；解析失败降级为
 * {@code OTHER + 空 tags + 空 description}，永不抛异常。
 *
 * <p>具体模型供应商和协议由 {@link VisionProvider} 负责，业务服务不直接依赖
 * Qwen-VL 或 OpenAI 兼容客户端。
 */
@Slf4j
@Service
public class VisionModelService {

    private final ObjectMapper objectMapper;
    private final VisionProvider visionProvider;

    @Autowired
    public VisionModelService(ObjectMapper objectMapper, VisionProvider visionProvider) {
        this.objectMapper = objectMapper;
        this.visionProvider = visionProvider;
    }

    /** Actual vision model identifier selected by the active runtime mode. */
    public String modelName() {
        return visionProvider.modelName();
    }

    /**
     * 识别图片内容，返回受控分类 + 标签 + 描述（解析失败降级，永不抛异常）。
     *
     * @param base64Data 图片字节的 Base64 编码（无 data URL 前缀）
     * @param mimeType   图片 MIME 类型（如 image/jpeg）
     * @param fileName   原始文件名（拼入提示词帮助模型理解）
     * @return 结构化识别结果；任何解析失败均降级为 {@code OTHER + 空 tags + 空 description}
     */
    @LogModelCall
    public VisionResult describeImage(String base64Data, String mimeType, String fileName) {
        String userPrompt = "请分析这张图片" + (fileName == null || fileName.isBlank() ? "" : "（文件名：" + fileName + "）")
                + "。请只输出 JSON，不要 Markdown 代码块。";
        ChatResponse response = visionProvider.chat(
                SystemMessage.from(buildSystemPrompt()),
                UserMessage.from(ImageContent.from(base64Data, mimeType), TextContent.from(userPrompt)));
        String reply = response.aiMessage() == null ? "" : response.aiMessage().text();
        return parseVisionResult(reply);
    }

    /**
     * 构建图片识别系统提示词：要求输出受控分类 JSON，词表经 {@code CategoryType} 的 label 列表注入。
     *
     * @return 系统提示词
     */
    private String buildSystemPrompt() {
        String categoryLabels = Arrays.stream(CategoryType.values())
                .map(CategoryType::getLabel)
                .collect(Collectors.joining("、"));
        return "你是一个专业的图片识别与分类助手。请分析图片内容，判断图片所属分类，"
                + "并生成 3 到 5 个中文关键词标签，以及一句话图片描述。\n"
                + "分类只能取以下受控值之一（用中文）：" + categoryLabels + "。判断务必保守："
                + "只有当画面内容与某一分类高度吻合、证据充分时才选择该具体分类。\n"
                + "「证件」仅限画面中清晰出现身份证、护照、驾驶证、营业执照等法定证照的拍摄或扫描件；"
                + "人物、风景、物品等普通照片属于「图片」，不属于证件。\n"
                + "画面内容难以可靠归类（随手拍、信息不足等）时一律取「其他」，不要强行归类。\n"
                + "请只输出一个 JSON 对象，格式为 {\"category\":\"分类\",\"tags\":[\"标签1\",\"标签2\"],\"description\":\"一句话描述\"}，"
                + "不要输出任何多余解释或 Markdown 代码块。";
    }

    /**
     * 解析模型回复为 {@link VisionResult}：剥离 Markdown 围栏 → JSON 解析 →
     * category 经 {@link CategoryType#fromLabel} 容错归一（未命中 OTHER）+ tags 数组
     * （trim/去空/distinct）+ description（textual 才取，否则空串）。
     * 任何解析失败均不抛异常，降级为 {@code OTHER + 空 tags + 空 description}。
     *
     * @param raw 模型原始回复
     * @return 结构化结果（永不抛异常）
     */
    VisionResult parseVisionResult(String raw) {
        if (raw == null || raw.isBlank()) {
            return new VisionResult(CategoryType.OTHER, List.of(), "");
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
            JsonNode descriptionNode = root.path("description");
            String description = descriptionNode.isTextual() ? descriptionNode.asText().trim() : "";
            return new VisionResult(category, tags, description);
        } catch (Exception e) {
            log.warn("视觉识别 JSON 解析失败，降级为 OTHER，异常类型={}", e.getClass().getSimpleName());
            return new VisionResult(CategoryType.OTHER, List.of(), "");
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
}
