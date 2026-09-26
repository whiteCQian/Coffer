package com.coffer.controller;

import com.coffer.dto.ChatRequest;
import com.coffer.dto.ChatQueryResponse;
import com.coffer.dto.Result;
import com.coffer.service.ConversationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 对话搜索接口。
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ConversationService conversationService;

    /**
     * 对话搜索：接收用户消息，Agent 自主决策是否调用搜索工具，返回回复与会话标识。
     *
     * @param request 请求体（sessionId 可选，message 必填）
     * @return 统一响应，data 为回复内容 + 会话标识（前端续聊需回传 sessionId）
     */
    @PostMapping("/send")
    @com.coffer.model.runtime.ModelSubmission("CHAT")
    public Result<ChatQueryResponse> chat(@RequestBody @Valid ChatRequest request) {
        var conversation = conversationService.sendMessageWithSources(request.getSessionId(), request.getMessage());
        return Result.success(ChatQueryResponse.builder()
                .reply(conversation.reply())
                .sessionId(conversation.sessionId())
                .citations(conversation.citations())
                .build());
    }
}
