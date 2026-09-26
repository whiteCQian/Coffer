package com.coffer.controller;

import com.coffer.dto.ChatCitation;
import com.coffer.service.ConversationResult;
import com.coffer.service.ConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对话搜索接口测试：验证回复与会话标识回传、空会话自动生成、空消息校验。
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class ChatControllerTest extends com.coffer.auth.OwnerModelSubmissionTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConversationService conversationService;

    @Test
    void chatReturnsReplyAndSessionId() throws Exception {
        when(conversationService.sendMessageWithSources("s1", "帮我找合同"))
                .thenReturn(new ConversationResult("找到3个合同文件", java.util.List.of(
                        ChatCitation.builder()
                                .fileId(12L)
                                .fileName("合同.pdf")
                                .fileType("pdf")
                                .snippet("合同摘要")
                                .score(0.82d)
                                .retrievalType("HYBRID")
                                .build()), "s1"));

        mockMvc.perform(post("/api/chat/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s1\",\"message\":\"帮我找合同\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.reply").value("找到3个合同文件"))
                .andExpect(jsonPath("$.data.sessionId").value("s1"))
                .andExpect(jsonPath("$.data.citations[0].fileId").value(12))
                .andExpect(jsonPath("$.data.citations[0].fileName").value("合同.pdf"))
                .andExpect(jsonPath("$.data.citations[0].retrievalType").value("HYBRID"));
    }

    @Test
    void chatBlankSessionGeneratesNewId() throws Exception {
        when(conversationService.sendMessageWithSources(nullable(String.class), anyString()))
                .thenReturn(new ConversationResult("你好", java.util.List.of(), "server-generated-session"));

        mockMvc.perform(post("/api/chat/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sessionId").value("server-generated-session"));

        // 空 ID 交给会话服务，由服务端为当前用户创建会话。
        verify(conversationService).sendMessageWithSources(isNull(), org.mockito.ArgumentMatchers.eq("你好"));
    }

    @Test
    void chatBlankMessageReturns400() throws Exception {
        mockMvc.perform(post("/api/chat/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
