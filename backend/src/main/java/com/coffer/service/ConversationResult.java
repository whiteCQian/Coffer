package com.coffer.service;

import com.coffer.dto.ChatCitation;

import java.util.List;

/** 一轮对话的回复文本与真实文件引用。 */
public record ConversationResult(String reply, List<ChatCitation> citations) {

    public ConversationResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
