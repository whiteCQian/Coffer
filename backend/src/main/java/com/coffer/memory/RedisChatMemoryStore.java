package com.coffer.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * LangChain4j 原生 {@link ChatMemoryStore}：将完整消息序列化到 Redis，供
 * {@code MessageWindowChatMemory} 持久化会话记忆。
 *
 * <p>使用官方 {@link ChatMessageSerializer}/{@link ChatMessageDeserializer} 做 JSON 往返，
 * 可完整还原包括工具调用帧（{@code AiMessage} 的工具调用、{@code ToolExecutionResultMessage}）
 * 在内的全部消息类型——这是自定义实体（仅 user_message/ai_response 两字段）无法承载的，
 * 此前因工具帧被丢弃导致 Agent 工具调用无限循环。
 *
 * <p>键为 {@code chat:memory:{id}}，过期统一交由 Redis 键 TTL 控制
 * （见 {@link #updateMessages} 写入时刷新的保留时长）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatMemoryStore implements ChatMemoryStore {

    /** Redis 键前缀：{@code chat:memory:{sessionId}}。 */
    public static final String KEY_PREFIX = "chat:memory:";

    /** 会话记忆保留时长（秒）：7 天；每次 {@link #updateMessages} 写入时刷新该 TTL（滑动过期）。 */
    private static final long DEFAULT_EXPIRY_SECONDS = 7 * 24 * 3600L;

    private final StringRedisTemplate redisTemplate;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + memoryId);
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return ChatMessageDeserializer.messagesFromJson(json);
        } catch (Exception e) {
            log.warn("反序列化会话记忆失败 memoryId={}: {}", memoryId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        redisTemplate.opsForValue().set(KEY_PREFIX + memoryId,
                ChatMessageSerializer.messagesToJson(messages),
                Duration.ofSeconds(DEFAULT_EXPIRY_SECONDS));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete(KEY_PREFIX + memoryId);
    }
}
