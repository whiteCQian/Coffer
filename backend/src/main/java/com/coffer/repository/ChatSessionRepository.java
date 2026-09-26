package com.coffer.repository;

import com.coffer.auth.infrastructure.OwnedRepository;
import com.coffer.entity.ChatSession;
import java.util.Optional;

public interface ChatSessionRepository extends OwnedRepository<ChatSession, Long> {
    Optional<ChatSession> findBySessionId(String sessionId);
}
