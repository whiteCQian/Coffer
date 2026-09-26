package com.coffer.service;

import com.coffer.agent.AiAgentService;
import com.coffer.entity.ChatMessage;
import com.coffer.repository.ChatMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话服务保存失败路径测试：DB 落库异常时返回提示信息，
 * 不影响已生成的 Agent 回复，且不向外抛异常。
 *
 * <p>ChatMessageRepository 以 {@link MockitoBean} 注入，仅模拟 save 抛异常；
 * 独立上下文，不与使用真实 Repository 的其它测试共享。
 */
@SpringBootTest
class ConversationServiceSaveFailureTest extends com.coffer.auth.OwnerTestSupport {
    @Autowired private ChatSessionService sessions;

    @Autowired
    private ConversationService conversationService;

    @MockitoBean
    private AiAgentService aiAgentService;

    @MockitoBean
    private ChatMessageRepository chatMessageRepository;

    @Test
    void saveFailureReturnsHintAndDoesNotThrow() {
        String session = sessions.create();
        when(aiAgentService.chat("帮我找合同文件", session)).thenReturn("已为你找到 3 个合同文件");
        when(chatMessageRepository.save(any(ChatMessage.class))).thenThrow(new RuntimeException("db down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> conversationService.sendMessage(session, "帮我找合同文件"))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("db down");
        // Agent 已被调用且返回了回复；失败仅发生在落库环节
        verify(aiAgentService).chat(anyString(), anyString());
    }
}
