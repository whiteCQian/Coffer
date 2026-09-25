package com.coffer.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话请求体（POST /api/chat/send）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    /** 会话标识，可选；为空则由服务端生成新会话。 */
    private String sessionId;

    /** 用户消息，不允许为空。 */
    @NotBlank(message = "消息内容不能为空")
    private String message;
}
