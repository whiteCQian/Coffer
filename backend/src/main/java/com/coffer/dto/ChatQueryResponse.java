package com.coffer.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话查询响应体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatQueryResponse {

    /** Agent 回复内容。 */
    private String reply;

    /** 会话标识（前端后续续聊需回传）。 */
    private String sessionId;

    /** 本轮回复实际使用的文件级引用。 */
    @Builder.Default
    private List<ChatCitation> citations = List.of();
}
