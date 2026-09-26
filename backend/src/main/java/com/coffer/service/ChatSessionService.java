package com.coffer.service;

import com.coffer.auth.service.OwnerAuthorization;
import com.coffer.auth.service.ResourceNotFoundException;
import com.coffer.entity.ChatSession;
import com.coffer.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.access.AccessDeniedException;

@Service
@RequiredArgsConstructor
public class ChatSessionService {
    private final OwnerAuthorization authorization;
    private final ChatSessionRepository sessions;
    // A bounded set of locks serializes complete turns, including Redis read/modify/write.
    private final Object[] locks = java.util.stream.IntStream.range(0, 256).mapToObj(i -> new Object()).toArray();

    public String create() {
        authorization.requireOwner();
        return sessions.save(new ChatSession(UUID.randomUUID().toString())).getSessionId();
    }

    public Long require(String sessionId) {
        Long owner = authorization.requireOwner();
        if (sessionId == null || !sessionId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new ResourceNotFoundException();
        ChatSession session = sessions.findBySessionId(sessionId).orElseThrow(ResourceNotFoundException::new);
        if (!owner.equals(session.getOwnerId())) throw new ResourceNotFoundException();
        return owner;
    }

    public void require(Long owner, String sessionId) {
        if (!require(sessionId).equals(owner)) throw new AccessDeniedException("无权执行此操作");
    }

    public <T> T inTurn(String sessionId, Supplier<T> action) {
        Long owner = require(sessionId);
        synchronized (locks[Math.floorMod((owner + ":" + sessionId).hashCode(), locks.length)]) {
            require(owner, sessionId);
            return action.get();
        }
    }
}
