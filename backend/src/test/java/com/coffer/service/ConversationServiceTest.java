package com.coffer.service;

import com.coffer.agent.AiAgentService;
import com.coffer.entity.ChatMessage;
import com.coffer.repository.ChatMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话服务集成测试：验证参数校验、会话 UUID 生成、对话落库与失败不落库。
 *
 * <p>AiAgentService 以 {@link MockitoBean} 注入（避免真实模型与 Agent 装配，
 * 保证单测确定性），对话记录落库使用真实 H2。
 */
@SpringBootTest
class ConversationServiceTest {

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @MockitoBean
    private AiAgentService aiAgentService;

    @Test
    void blankUserMessageReturnsErrorWithoutSaving() {
        String result = conversationService.sendMessage("s-blank-1", "   ");
        assertThat(result).isEqualTo("消息不能为空");
        assertThat(chatMessageRepository.findBySessionIdOrderByTimestampAsc("s-blank-1")).isEmpty();
    }

    @Test
    void blankSessionGeneratesUuidAndPersists() {
        when(aiAgentService.chat(anyString(), anyString())).thenReturn("你好，Coffer");

        String reply = conversationService.sendMessage("  ", "帮我找合同文件");
        assertThat(reply).isEqualTo("你好，Coffer");

        // 捕获实际传给 Agent 的生成会话 ID，并校验其已落库一条记录
        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiAgentService).chat(anyString(), sessionCaptor.capture());
        String generated = sessionCaptor.getValue();
        assertThat(generated).isNotBlank();

        List<ChatMessage> rows = chatMessageRepository.findBySessionIdOrderByTimestampAsc(generated);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getUserMessage()).isEqualTo("帮我找合同文件");
        assertThat(rows.get(0).getAiResponse()).isEqualTo("你好，Coffer");
        assertThat(rows.get(0).getTimestamp()).isNotNull();
    }

    @Test
    void normalFlowPersistsMessage() {
        when(aiAgentService.chat("帮我找合同文件", "s-save-1")).thenReturn("已为你找到 3 个合同文件");

        String reply = conversationService.sendMessage("s-save-1", "帮我找合同文件");
        assertThat(reply).isEqualTo("已为你找到 3 个合同文件");

        List<ChatMessage> rows = chatMessageRepository.findBySessionIdOrderByTimestampAsc("s-save-1");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getUserMessage()).isEqualTo("帮我找合同文件");
        assertThat(rows.get(0).getAiResponse()).isEqualTo("已为你找到 3 个合同文件");
        assertThat(rows.get(0).getTimestamp()).isNotNull();
    }

    @Test
    void chatFailureReturnsFriendlyAndDoesNotSave() {
        when(aiAgentService.chat(anyString(), anyString())).thenThrow(new RuntimeException("boom"));

        String reply = conversationService.sendMessage("s-fail-1", "帮我找合同文件");
        assertThat(reply).isEqualTo("对话处理失败，请稍后重试");
        assertThat(chatMessageRepository.findBySessionIdOrderByTimestampAsc("s-fail-1")).isEmpty();
    }
}
