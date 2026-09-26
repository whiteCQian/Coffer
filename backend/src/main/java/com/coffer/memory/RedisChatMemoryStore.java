package com.coffer.memory;

import com.coffer.service.ChatCitationCollector;
import com.coffer.service.ChatSessionService;
import com.coffer.service.PrivateFileAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;

/** Versioned envelopes deliberately do not import unowned legacy Redis keys. */
@Component
@RequiredArgsConstructor
public class RedisChatMemoryStore implements ChatMemoryStore {
    public static final String KEY_PREFIX = "chat:memory:v2:";
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ChatSessionService sessions;
    private final PrivateFileAccess files;
    private final ChatCitationCollector citations;

    private String key(Object value) {
        if (!(value instanceof OwnerMemoryId id)) throw new AccessDeniedException("无权执行此操作");
        sessions.require(id.ownerId(), id.sessionId());
        return KEY_PREFIX + id.ownerId() + ":" + id.sessionId();
    }
    private Envelope read(String key, boolean writing) {
        String raw = redis.opsForValue().get(key);
        if (raw == null) return null;
        Envelope envelope;
        try { envelope = json.readValue(raw, Envelope.class); }
        catch (Exception invalid) { redis.delete(key); return null; }
        if (envelope.revisions() == null || envelope.messages() == null || envelope.revisions().entrySet().stream()
                .anyMatch(e -> !files.current(e.getKey(), e.getValue()))) {
            redis.delete(key);
            if (writing) throw new com.coffer.service.FileVersionConflictException();
            return null;
        }
        return envelope;
    }
    @Override public List<ChatMessage> getMessages(Object id) {
        String key = key(id);
        Envelope envelope = read(key, false);
        if (envelope == null) return List.of();
        try { return ChatMessageDeserializer.messagesFromJson(envelope.messages()); }
        catch (Exception invalid) { redis.delete(key); return List.of(); }
    }
    @Override public void updateMessages(Object id, List<ChatMessage> messages) {
        String key = key(id);
        Envelope previous = read(key, true);
        Map<Long, Long> revisions = new LinkedHashMap<>();
        if (previous != null) revisions.putAll(previous.revisions());
        revisions.putAll(citations.revisions());
        if (revisions.entrySet().stream().anyMatch(e -> !files.current(e.getKey(), e.getValue()))) {
            redis.delete(key);
            throw new com.coffer.service.FileVersionConflictException();
        }
        try {
            redis.opsForValue().set(key, json.writeValueAsString(new Envelope(
                    ChatMessageSerializer.messagesToJson(messages), revisions)), Duration.ofDays(7));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("会话记忆保存失败", invalid);
        }
    }
    @Override public void deleteMessages(Object id) { redis.delete(key(id)); }
    public record Envelope(String messages, Map<Long, Long> revisions) {}
}
