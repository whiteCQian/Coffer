package com.coffer.tag.application;

import com.coffer.annotation.LogModelCall;
import com.coffer.model.provider.ChatProvider;
import com.coffer.tag.api.dto.TagCandidateResponse;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 标签联想服务：把用户的自然语言描述转成<b>文件库中真实存在</b>的标签，供前端点选后按标签精确检索。
 *
 * <p>设计要点（对齐产品「靠标签不靠文件夹」）：
 * <ul>
 *   <li><b>不自由编造</b>：模型只能在 {@code FileTagMappingRepository#findTagCandidates}
 *       返回的真实标签（已被文件确认采用，按覆盖数倒序）里挑选，池外名字一律丢弃；</li>
 *   <li><b>字面优先</b>：查询词与候选标签存在包含关系时直接返回（零延迟零成本），
 *       只有字面未命中才调用 DeepSeek 做语义联想；</li>
 *   <li><b>失败降级</b>：模型调用/JSON 解析失败返回空列表，前端自然回退到普通关键词搜索。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagSuggestionService {

    /** 语义联想系统提示词，候选词表经 {@code {candidates}} 注入，查询词作为用户消息。 */
    private static final String SYSTEM_PROMPT_TEMPLATE =
            "你是一个文件检索助手，负责把用户的自然语言描述转换成文件库中真实存在的标签，供后续按标签精确检索文件。\n"
                    + "下面是文件库中真实存在且被文件确认采用的标签清单（每行：标签名（覆盖文件数））：\n"
                    + "{candidates}\n"
                    + "请从这份清单中挑选与用户描述最相关的 1 到 3 个标签。要求：只能挑清单里原样存在的名字，绝不能编造；"
                    + "若清单里没有任何标签贴近用户描述，则返回空数组。\n"
                    + "只输出一个 JSON 对象，格式为 {\"tags\":[\"标签1\",\"标签2\"]}，不要输出任何多余解释或 Markdown 代码块。";

    /** 参与 LLM 挑选的最大候选数量（按热度截断，控制上下文与噪声）。 */
    private static final int LLM_CANDIDATE_WINDOW = 80;

    /** 语义联想返回的最大标签数。 */
    private static final int SUGGEST_LIMIT = 3;

    /** 字面命中的最大返回数（查询词包含多个候选名时截断）。 */
    private static final int LITERAL_LIMIT = 5;

    private final ChatProvider chatProvider;
    private final ObjectMapper objectMapper;
    private final FileTagMappingRepository fileTagMappingRepository;

    /** Returns the confirmed tag pool used by both the HTTP API and semantic suggestions. */
    public List<TagCandidateResponse> listCandidates() {
        return toCandidates();
    }

    /**
     * 根据查询描述联想候选标签（真实标签，含覆盖文件数，按热度/相关度排序）。
     *
     * @param query 用户输入的自然语言描述
     * @return 候选标签列表；查询为空、库中无已确认标签或联想失败时返回空列表（永不抛异常）
     */
    @LogModelCall
    public List<TagCandidateResponse> suggest(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.trim();
        List<TagCandidateResponse> pool = listCandidates();
        if (pool.isEmpty()) {
            return List.of();
        }
        // 字面命中（含「查询词含候选名」的边输入边联想）：直接返回，不调模型
        List<TagCandidateResponse> literal = literalMatch(q, pool);
        if (!literal.isEmpty()) {
            return literal;
        }
        // 太短的查询不值得一次模型调用
        if (q.length() < 2) {
            return List.of();
        }
        // 语义联想：仅从池中挑，池外名字丢弃
        return pickByModel(q, pool);
    }

    /** 候选池：CONFIRMED 标签名 + 覆盖文件数，按覆盖数倒序（Repository 已排）。 */
    private List<TagCandidateResponse> toCandidates() {
        return fileTagMappingRepository.findTagCandidates().stream()
                .map(tc -> TagCandidateResponse.builder()
                        .name(tc.getName())
                        .fileCount(tc.getCnt())
                        .build())
                .toList();
    }

    /**
     * 字面命中：候选名与查询词存在包含关系（忽略大小写）即命中，保持池内热度顺序。
     */
    private List<TagCandidateResponse> literalMatch(String q, List<TagCandidateResponse> pool) {
        String lowerQ = q.toLowerCase();
        List<TagCandidateResponse> hits = new ArrayList<>();
        for (TagCandidateResponse c : pool) {
            String name = c.getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            String lowerName = name.toLowerCase();
            // 双向包含：输入补全（候选含查询词）与 混排含词（查询词含候选名）
            if (lowerName.contains(lowerQ) || lowerQ.contains(lowerName)) {
                hits.add(c);
                if (hits.size() >= LITERAL_LIMIT) {
                    break;
                }
            }
        }
        return hits;
    }

    /** 语义联想：候选池按热度截断窗口 → 调模型挑 1~3 → 过滤池外名 → 按选择顺序返回。 */
    private List<TagCandidateResponse> pickByModel(String q, List<TagCandidateResponse> pool) {
        List<TagCandidateResponse> window = pool.size() > LLM_CANDIDATE_WINDOW
                ? pool.subList(0, LLM_CANDIDATE_WINDOW)
                : pool;
        String candidatesText = window.stream()
                .map(c -> c.getName() + "（" + c.getFileCount() + " 个文件）")
                .collect(Collectors.joining("\n"));
        String systemPrompt = SYSTEM_PROMPT_TEMPLATE.replace("{candidates}", candidatesText);
        String reply = callModel(systemPrompt, q);
        List<String> chosen = parseTagChoices(reply);
        if (chosen.isEmpty()) {
            return List.of();
        }
        Map<String, Long> nameToCount = new LinkedHashMap<>();
        for (TagCandidateResponse c : window) {
            nameToCount.put(c.getName(), c.getFileCount());
        }
        List<TagCandidateResponse> result = new ArrayList<>();
        for (String name : chosen) {
            Long count = nameToCount.get(name);
            if (count != null && result.size() < SUGGEST_LIMIT) { // 仅保留池内真实标签
                result.add(TagCandidateResponse.builder().name(name).fileCount(count).build());
            }
        }
        return result;
    }

    /** 调用模型返回 AI 消息文本。 */
    private String callModel(String systemPrompt, String userText) {
        ChatResponse response = chatProvider.chat(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userText));
        return response.aiMessage() == null ? "" : response.aiMessage().text();
    }

    /**
     * 解析模型回复为标签名列表：剥离 Markdown 围栏 → JSON 解析 → 取 {@code tags} 数组。
     * 任何解析失败均返回空列表（调用方据此降级）。
     */
    private List<String> parseTagChoices(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(stripCodeFence(raw));
            JsonNode tagsNode = root.path("tags");
            if (!tagsNode.isArray()) {
                return List.of();
            }
            List<String> tags = new ArrayList<>();
            for (JsonNode node : tagsNode) {
                String clean = (node.isTextual() ? node.asText() : node.toString()).trim();
                if (!clean.isEmpty() && !tags.contains(clean)) {
                    tags.add(clean);
                }
            }
            return tags;
        } catch (Exception e) {
            log.warn("标签联想 JSON 解析失败，返回空列表: {}", raw, e);
            return List.of();
        }
    }

    /** 剥离模型可能返回的 Markdown 代码块围栏（```json ... ```）。 */
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
