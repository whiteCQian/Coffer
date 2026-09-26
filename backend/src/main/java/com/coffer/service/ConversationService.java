package com.coffer.service;

import com.coffer.agent.AiAgentService;
import com.coffer.entity.ChatMessage;
import com.coffer.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

@Service
@com.coffer.auth.service.OwnerOnly
@RequiredArgsConstructor
public class ConversationService {
    private final AiAgentService agent;
    private final ChatMessageRepository messages;
    private final ChatCitationCollector citations;
    private final ChatSessionService sessions;

    public String sendMessage(String sessionId, String message) {
        return sendMessageWithSources(sessionId, message).reply();
    }
    public ConversationResult sendMessageWithSources(String sessionId, String message) {
        if (message == null || message.isBlank()) throw new IllegalArgumentException("消息不能为空");
        String id = sessionId == null || sessionId.isBlank() ? sessions.create() : sessionId;
        return sessions.inTurn(id, () -> {
            citations.begin();
            try {
                String reply = agent.chat(message, id);
                var sources = citations.finish();
                sessions.require(id);
                messages.save(ChatMessage.builder().sessionId(id).userMessage(message)
                        .modelSnapshotId(com.coffer.model.runtime.ModelExecutionContext.currentId())
                        .aiResponse(reply).timestamp(LocalDateTime.now()).build());
                return new ConversationResult(reply, sources, id);
            } finally { citations.clear(); }
        });
    }
}
