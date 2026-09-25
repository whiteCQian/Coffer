package com.coffer.controller;

import com.coffer.dto.ChatRequest;
import com.coffer.dto.ChatQueryResponse;
import com.coffer.dto.Result;
import com.coffer.service.ConversationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 对话搜索接口。
 */
@Slf4j
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
    public Result<ChatQueryResponse> chat(@RequestBody @Valid ChatRequest request) {
        // 会话为空时在 Controller 层生成，保证能回传给前端续聊
        String sessionId = (request.getSessionId() == null || request.getSessionId().isBlank())
                ? UUID.randomUUID().toString()
                : request.getSessionId();
        log.info("对话请求 sessionId={}, message={}", sessionId, request.getMessage());

        var conversation = conversationService.sendMessageWithSources(sessionId, request.getMessage());
        return Result.success(ChatQueryResponse.builder()
                .reply(conversation.reply())
                .sessionId(sessionId)
                .citations(conversation.citations())
                .build());
    }
}
