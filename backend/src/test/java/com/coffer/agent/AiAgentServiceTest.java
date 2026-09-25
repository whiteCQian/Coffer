package com.coffer.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.coffer.model.provider.ChatProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * AiAgentService 编排测试：验证 Bean 装配（AiServices 代理 @PostConstruct 构建成功）、
 * 上下文拼接、对话调用与参数校验。模型调用用 {@link MockitoBean} 模拟，
 * 记忆走真实 Redis（会话记忆在调用后持久化）。
 */
@SpringBootTest
class AiAgentServiceTest {

    @Autowired
    private AiAgentService aiAgentService;

    /** 模型依赖 Mock：验证编排链路，真实 DeepSeek 调用留到启动实测。 */
    @MockitoBean
    private ChatProvider chatProvider;

    @Test
    void chatWithMockedModel() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("你好，我是 Coffer 智能文件管家，已理解你的需求"))
                .build();
        // AiServices 执行器可能命中不同重载，统一桩返回固定回复
        when(chatProvider.chat(any(ChatRequest.class))).thenReturn(response);
        when(chatProvider.chat(any(ChatMessage[].class))).thenReturn(response);
        when(chatProvider.chat(anyList())).thenReturn(response);

        String reply = aiAgentService.chat("帮我找合同文件", "session-" + System.nanoTime());

        assertNotNull(reply, "回复不能为 null");
        assertTrue(reply.contains("Coffer"), "应返回模型回复，实际: " + reply);
        System.out.println("AiAgentService chat 测试通过，回复: " + reply);
    }

    @Test
    void chatWithEmptyParams() {
        assertEquals("消息不能为空，请提供需要处理的内容", aiAgentService.chat("   ", "s1"));
        assertEquals("会话 ID 不能为空", aiAgentService.chat("你好", "  "));
    }
}
