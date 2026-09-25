package com.coffer.service;

import com.coffer.agent.AiAgentService;
import com.coffer.dto.ChatCitation;
import com.coffer.entity.ChatMessage;
import com.coffer.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 对话搜索管道服务：作为用户消息与 Agent 交互的统一入口。
 *
 * <p>会话标识与用户消息校验后，委托 {@link AiAgentService#chat} 交由 LangChain4j AiServices
 * 依据系统提示词与绑定的工具（含 {@code search_files}）自主决策是否调用搜索工具；
 * 成功后将该轮对话持久化到 DB（{@link ChatMessage}）支撑对话历史。
 * 调用异常时记录错误日志并返回友好提示，不保存失败的对话记录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final AiAgentService aiAgentService;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatCitationCollector citationCollector;

    /**
     * 发送对话消息：校验参数 → 调用 Agent → 保存对话记录 → 返回回复。
     *
     * @param sessionId   会话标识；为空则自动生成 UUID
     * @param userMessage 用户输入文本；为空返回错误提示
     * @return Agent 回复字符串；参数非法或调用异常时返回提示信息
     */
    @Transactional
    public String sendMessage(String sessionId, String userMessage) {
        return sendMessageWithSources(sessionId, userMessage).reply();
    }

    /**
     * 发送对话消息并返回 Agent 回复及本轮真实文件引用。
     *
     * <p>保留 {@link #sendMessage(String, String)} 作为兼容入口，已有内部调用方仍可
     * 只获取回复文本；HTTP 接口使用本方法透传引用。</p>
     */
    @Transactional
    public ConversationResult sendMessageWithSources(String sessionId, String userMessage) {
        citationCollector.begin();

        // 1. 参数校验：会话为空生成新 UUID，消息为空返回错误提示
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }
        if (userMessage == null || userMessage.isBlank()) {
            return new ConversationResult("消息不能为空", citationCollector.finish());
        }

        // 2. 调用 Agent（AiServices 依据系统提示词 + search_files 工具自主决策是否搜索）
        String reply;
        try {
            reply = aiAgentService.chat(userMessage, sessionId);
        } catch (Exception e) {
            // 3. 调用异常：记录错误日志，不保存失败对话，返回友好提示
            log.error("对话处理失败 sessionId={}: {}", sessionId, e.getMessage(), e);
            return new ConversationResult("对话处理失败，请稍后重试", citationCollector.finish());
        }
        List<ChatCitation> citations = citationCollector.finish();

        // 4. 保存对话记录：用户问题 + AI 回答同轮入库（同步保存，避免异步事务边界问题）
        try {
            chatMessageRepository.save(ChatMessage.builder()
                    .sessionId(sessionId)
                    .userMessage(userMessage)
                    .aiResponse(reply)
                    .timestamp(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            // 5. 保存失败：仅记录日志并返回提示，不影响已生成的回复内容
            log.error("对话记录保存失败 sessionId={}: {}", sessionId, e.getMessage(), e);
            return new ConversationResult("对话记录保存失败，但响应已生成", citations);
        }

        return new ConversationResult(reply, citations);
    }
}
