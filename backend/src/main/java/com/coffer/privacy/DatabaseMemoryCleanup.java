package com.coffer.privacy;

import com.coffer.memory.ChatMemoryRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component @Profile("desktop | prod") @RequiredArgsConstructor @com.coffer.auth.service.OwnerOnly
public class DatabaseMemoryCleanup implements MemoryCleanup {
    private final ChatMemoryRecordRepository records;
    @Transactional public void delete(String sessionId) {
        java.util.UUID.fromString(sessionId);
        records.deleteBySessionId(sessionId);
    }
}
