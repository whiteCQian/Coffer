package com.coffer.privacy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.coffer.auth.service.*;
import com.coffer.memory.RedisChatMemoryStore;

@Service @RequiredArgsConstructor @OwnerOnly
public class MemoryDeletionWorker {
    private final MemoryDeletionRepository pending;
    private final StringRedisTemplate redis;
    @OwnerScheduled @Scheduled(fixedDelayString = "${coffer.privacy.cleanup-delay-ms:30000}")
    public void process() {
        for (var row : pending.findAll(org.springframework.data.domain.PageRequest.of(0, 100))) {
            try {
                java.util.UUID.fromString(row.getSessionId());
                redis.delete(RedisChatMemoryStore.KEY_PREFIX + TenantContext.requireOwnerId() + ":" + row.getSessionId());
                pending.delete(row);
            } catch (RuntimeException unavailable) {
                // Keep exact session IDs for a later retry; never log Redis keys or content.
                return;
            }
        }
    }
}
