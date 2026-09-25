package com.coffer.controller;

import com.coffer.dto.ChatCitation;
import com.coffer.service.ConversationResult;
import com.coffer.service.ConversationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对话搜索接口测试：验证回复与会话标识回传、空会话自动生成、空消息校验。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatControllerTest {

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
                                .build())));

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
        when(conversationService.sendMessageWithSources(anyString(), anyString()))
                .thenReturn(new ConversationResult("你好", java.util.List.of()));

        mockMvc.perform(post("/api/chat/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sessionId").isNotEmpty());

        // 服务端生成的会话 ID 应透传给 ConversationService
        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        verify(conversationService).sendMessageWithSources(sessionCaptor.capture(), anyString());
        assertThat(sessionCaptor.getValue()).isNotBlank();
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
