package com.coffer.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话记忆 Redis 存储测试：验证官方序列化 codec 能完整往返包括工具调用帧在内的全部消息。
 *
 * <p>此前自定义适配器（仅 user_message/ai_response 两字段）在往返时丢弃工具帧，
 * 导致 Agent 工具调用无限循环；本测试锁定「工具帧必须可还原」这一关键行为。
 */
@SpringBootTest
class RedisChatMemoryStoreTest {

    @Autowired
    private RedisChatMemoryStore store;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String MEMORY_ID = "test-memory-store";

    @AfterEach
    void cleanup() {
        redisTemplate.delete(RedisChatMemoryStore.KEY_PREFIX + MEMORY_ID);
    }

    @Test
    void roundTripUserAiAndToolFrames() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call_01").name("search_files").arguments("{\"queryKeyword\":\"contract\"}").build();
        List<ChatMessage> messages = List.of(
                UserMessage.from("find contract files"),
                AiMessage.from(request),
                ToolExecutionResultMessage.from("call_01", "search_files", "找到 1 个文件：合同.txt"),
                AiMessage.from("已找到 1 个合同文件。"));

        store.updateMessages(MEMORY_ID, messages);
        List<ChatMessage> restored = store.getMessages(MEMORY_ID);

        assertThat(restored).hasSize(4);
        // 工具调用帧（AiMessage + ToolExecutionResultMessage）必须完整还原
        assertThat(restored.get(1)).isInstanceOf(AiMessage.class);
        AiMessage toolCall = (AiMessage) restored.get(1);
        assertThat(toolCall.toolExecutionRequests()).hasSize(1);
        assertThat(toolCall.toolExecutionRequests().get(0).id()).isEqualTo("call_01");
        assertThat(toolCall.toolExecutionRequests().get(0).name()).isEqualTo("search_files");
        assertThat(restored.get(2)).isInstanceOf(ToolExecutionResultMessage.class);
        assertThat(((ToolExecutionResultMessage) restored.get(2)).id()).isEqualTo("call_01");
        // 最终回答文本完整保留
        assertThat(((AiMessage) restored.get(3)).text()).isEqualTo("已找到 1 个合同文件。");
    }

    @Test
    void emptyKeyReturnsEmptyList() {
        assertThat(store.getMessages("nonexistent-" + MEMORY_ID)).isEmpty();
    }
}
