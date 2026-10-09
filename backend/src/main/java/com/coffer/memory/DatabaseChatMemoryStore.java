package com.coffer.memory;

import com.coffer.model.runtime.ModelExecutionContext;
import com.coffer.service.ChatCitationCollector;
import com.coffer.service.ChatSessionService;
import com.coffer.service.FileVersionConflictException;
import com.coffer.service.PrivateFileAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** Desktop memory uses the same authorized tool-frame/revision envelope as server memory. */
@Service @Profile("desktop") @RequiredArgsConstructor @Transactional
public class DatabaseChatMemoryStore implements ChatMemoryStore {
    private final ChatMemoryRecordRepository records;
    private final ObjectMapper json;
    private final ChatSessionService sessions;
    private final PrivateFileAccess files;
    private final ChatCitationCollector citations;

    private OwnerMemoryId authorize(Object value) {
        if (!(value instanceof OwnerMemoryId id)) throw new AccessDeniedException("无权执行此操作");
        sessions.require(id.ownerId(), id.sessionId());
        return id;
    }

    private RedisChatMemoryStore.Envelope read(OwnerMemoryId id, boolean writing) {
        var row = records.findBySessionId(id.sessionId());
        if (row.isEmpty()) return null;
        var record = row.orElseThrow();
        if (!record.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            records.delete(record); return null;
        }
        RedisChatMemoryStore.Envelope envelope;
        try { envelope = json.readValue(record.getEnvelope(), RedisChatMemoryStore.Envelope.class); }
        catch (Exception invalid) { records.delete(record); return null; }
        var current = ModelExecutionContext.current();
        if (current != null && !Objects.equals(current.version(), envelope.configurationVersion())) {
            records.delete(record); return null;
        }
        if (envelope.revisions() == null || envelope.messages() == null
                || envelope.revisions().entrySet().stream().anyMatch(e -> !files.current(e.getKey(), e.getValue()))) {
            records.delete(record);
            if (writing) throw new FileVersionConflictException();
            return null;
        }
        return envelope;
    }

    @Override public List<ChatMessage> getMessages(Object value) {
        var id = authorize(value);
        var envelope = read(id, false);
        if (envelope == null) return List.of();
        try { return ChatMessageDeserializer.messagesFromJson(envelope.messages()); }
        catch (Exception invalid) { records.deleteBySessionId(id.sessionId()); return List.of(); }
    }

    @Override public void updateMessages(Object value, List<ChatMessage> messages) {
        var id = authorize(value);
        var previous = read(id, true);
        Map<Long, Long> revisions = new LinkedHashMap<>();
        if (previous != null) revisions.putAll(previous.revisions());
        revisions.putAll(citations.revisions());
        if (revisions.entrySet().stream().anyMatch(e -> !files.current(e.getKey(), e.getValue())))
            throw new FileVersionConflictException();
        try {
            var current = ModelExecutionContext.current();
            var row = records.findBySessionId(id.sessionId()).orElseGet(() -> new ChatMemoryRecord(id.sessionId()));
            row.setEnvelope(json.writeValueAsString(new RedisChatMemoryStore.Envelope(
                    ChatMessageSerializer.messagesToJson(messages), revisions, current == null ? null : current.version())));
            row.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(7));
            records.saveAndFlush(row);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("会话记忆保存失败", invalid);
        }
    }

    @Override public void deleteMessages(Object value) {
        records.deleteBySessionId(authorize(value).sessionId());
    }

    @com.coffer.auth.service.OwnerScheduled
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${coffer.memory.cleanup-delay-ms:3600000}")
    public void expire() { records.deleteByExpiresAtBefore(LocalDateTime.now(ZoneOffset.UTC)); }
}
