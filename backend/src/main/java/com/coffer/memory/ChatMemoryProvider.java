package com.coffer.memory;

import com.coffer.service.ChatSessionService;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Every access, including retained memory objects, reaches the guarded store. */
@Component
public class ChatMemoryProvider {
    private final RedisChatMemoryStore store;
    private final ChatSessionService sessions;
    private final int maxMessages;
    public ChatMemoryProvider(RedisChatMemoryStore store, ChatSessionService sessions,
                              @Value("${coffer.memory.max-window-size:10}") int maxMessages) {
        this.store = store;
        this.sessions = sessions;
        this.maxMessages = maxMessages;
    }
    public ChatMemory getMemory(String sessionId) {
        OwnerMemoryId id = new OwnerMemoryId(sessions.require(sessionId), sessionId);
        return MessageWindowChatMemory.builder().id(id).maxMessages(maxMessages).chatMemoryStore(store).build();
    }
    public void clearMemory(String sessionId) {
        store.deleteMessages(new OwnerMemoryId(sessions.require(sessionId), sessionId));
    }
}
