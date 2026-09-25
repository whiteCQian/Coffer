package com.coffer.memory;

import com.coffer.entity.ChatMessage;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 Redis List 的会话记忆实现（单会话实例）。
 *
 * <p>由 {@link ChatMemoryProvider} 按 sessionId 创建，一个实例固定绑定一个会话，
 * Redis 键为 {@code chat:memory:{sessionId}}（sessionId 在构造时确定）。
 * 追加消息时若列表长度超过最大窗口则移除最早消息，并对键续期（TTL 7 天，
 * 与定时清理任务 {@code CleanupScheduledTask} 的保留策略一致）。
 *
 * <p>本实现接口方法中的 sessionId 参数保留以兼容 {@link ChatMemory} 接口签名，
 * 实际键均基于构造时绑定的 sessionId 生成，保证会话记忆相互隔离。
 */
public class RedisChatMemory implements ChatMemory {

    /** Redis 键前缀。 */
    public static final String KEY_PREFIX = "chat:memory:";

    /** 会话记忆过期时间（秒）：7 天，与定时清理任务（CleanupScheduledTask）的保留策略一致。 */
    private static final long DEFAULT_EXPIRY_SECONDS = 7 * 24 * 3600L;

    private final String sessionId;
    private final RedisTemplate<String, Object> redisTemplate;
    private final int maxWindowSize;

    public RedisChatMemory(String sessionId, RedisTemplate<String, Object> redisTemplate, int maxWindowSize) {
        this.sessionId = sessionId;
        this.redisTemplate = redisTemplate;
        this.maxWindowSize = maxWindowSize;
    }

    /** 返回本实例绑定的会话 ID。 */
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public void add(String sessionId, ChatMessage message) {
        String key = buildKey(this.sessionId);
        redisTemplate.opsForList().rightPush(key, message);
        Long size = redisTemplate.opsForList().size(key);
        if (size != null && size > maxWindowSize) {
            redisTemplate.opsForList().leftPop(key);
        }
        redisTemplate.expire(key, Duration.ofSeconds(DEFAULT_EXPIRY_SECONDS));
    }

    @Override
    public List<ChatMessage> get(String sessionId) {
        List<Object> messages = redisTemplate.opsForList().range(buildKey(this.sessionId), 0, -1);
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .filter(ChatMessage.class::isInstance)
                .map(ChatMessage.class::cast)
                .collect(Collectors.toList());
    }

    @Override
    public void clear(String sessionId) {
        redisTemplate.delete(buildKey(this.sessionId));
    }

    private String buildKey(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
