package com.coffer.memory;

import com.coffer.entity.ChatMessage;

import java.util.List;

/**
 * 基于会话 ID 的对话记忆接口。
 *
 * <p>每个列表元素代表一轮对话（一条 {@link ChatMessage}），
 * 与 LangChain4j 的 ChatMemory 签名不同（本接口显式携带 sessionId），
 * 支持多会话并存。
 */
public interface ChatMemory {

    /**
     * 追加一轮对话到指定会话，超出最大窗口时移除最早一轮。
     *
     * @param sessionId 会话 ID
     * @param message   本轮对话
     */
    void add(String sessionId, ChatMessage message);

    /**
     * 获取指定会话的全部对话历史（按时间先后）。
     *
     * @param sessionId 会话 ID
     * @return 对话历史列表，无记录时返回空列表
     */
    List<ChatMessage> get(String sessionId);

    /**
     * 清除指定会话的对话记忆。
     *
     * @param sessionId 会话 ID
     */
    void clear(String sessionId);
}
