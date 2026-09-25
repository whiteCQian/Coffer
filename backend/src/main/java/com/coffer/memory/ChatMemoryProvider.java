package com.coffer.memory;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话记忆提供者：按 sessionId 创建/获取独立的 LangChain4j 原生 {@link MessageWindowChatMemory} 实例，
 * 由 {@link RedisChatMemoryStore} 持久化到 Redis（官方序列化 codec，可承载工具调用帧），
 * 供 AiServices 原生记忆使用。
 *
 * <p>通过本地缓存 {@link ConcurrentHashMap} 持有各会话的记忆实例，避免重复创建；
 * Redis 键自身带 TTL（7 天），可调用 {@link #clearAllExpired()} 定时清理已过期会话。
 */
@Slf4j
@Component
public class ChatMemoryProvider {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisChatMemoryStore chatMemoryStore;
    private final int maxWindowSize;
    private final long expirySeconds;
    private final ConcurrentHashMap<String, ChatMemory> memoryCache = new ConcurrentHashMap<>();

    public ChatMemoryProvider(RedisTemplate<String, Object> redisTemplate,
                              RedisChatMemoryStore chatMemoryStore,
                              @Value("${coffer.memory.max-window-size:10}") int maxWindowSize,
                              @Value("${coffer.memory.expiry-seconds:3600}") long expirySeconds) {
        this.redisTemplate = redisTemplate;
        this.chatMemoryStore = chatMemoryStore;
        this.maxWindowSize = maxWindowSize;
        this.expirySeconds = expirySeconds;
    }

    /**
     * 获取指定会话的记忆实例，不存在则创建并存入本地缓存。
     *
     * @param sessionId 会话 ID
     * @return 该会话的 {@link MessageWindowChatMemory} 实例（内部经 {@link RedisChatMemoryStore} 持久化）
     */
    public ChatMemory getMemory(String sessionId) {
        return memoryCache.computeIfAbsent(sessionId,
                id -> MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(maxWindowSize)
                        .chatMemoryStore(chatMemoryStore)
                        .build());
    }

    /**
     * 删除指定会话的 Redis 记忆键，并从本地缓存移除对应实例。
     *
     * @param sessionId 会话 ID
     */
    public void clearMemory(String sessionId) {
        memoryCache.remove(sessionId);
        chatMemoryStore.deleteMessages(sessionId);
    }

    /**
     * 定时清理已过期会话：Redis 键已失效（TTL 到期或被删除）的实例从本地缓存移除。
     *
     * <p>扫描周期取配置的 {@code coffer.memory.expiry-seconds}（默认 3600 秒），
     * 与记忆键的 TTL 一致，保证缓存中不会残留失效会话。
     */
    @Scheduled(fixedDelayString = "${coffer.memory.expiry-seconds:3600}000")
    public void clearAllExpired() {
        try {
            long before = memoryCache.size();
            memoryCache.entrySet().removeIf(entry ->
                    Boolean.FALSE.equals(redisTemplate.hasKey(RedisChatMemoryStore.KEY_PREFIX + entry.getKey())));
            if (before != memoryCache.size()) {
                log.info("清理过期会话记忆: {} -> {}", before, memoryCache.size());
            }
        } catch (Exception e) {
            log.debug("清理过期会话记忆失败（Redis 可能未启动）: {}", e.getMessage());
        }
    }
}
