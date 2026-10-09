package com.coffer.memory;

import com.coffer.auth.infrastructure.OwnedRepository;
import java.util.Optional;
import java.time.LocalDateTime;

public interface ChatMemoryRecordRepository extends OwnedRepository<ChatMemoryRecord, Long> {
    Optional<ChatMemoryRecord> findBySessionId(String sessionId);
    void deleteBySessionId(String sessionId);
    void deleteByExpiresAtBefore(LocalDateTime cutoff);
}
