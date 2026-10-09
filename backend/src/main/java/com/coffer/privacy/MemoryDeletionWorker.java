package com.coffer.privacy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import com.coffer.auth.service.*;

@Service @RequiredArgsConstructor @OwnerOnly
public class MemoryDeletionWorker {
    private final MemoryDeletionRepository pending;
    private final MemoryCleanup cleanup;
    @OwnerScheduled @Scheduled(fixedDelayString = "${coffer.privacy.cleanup-delay-ms:30000}")
    public void process() {
        for (var row : pending.findAll(org.springframework.data.domain.PageRequest.of(0, 100))) {
            try {
                java.util.UUID.fromString(row.getSessionId());
                cleanup.delete(row.getSessionId());
                pending.delete(row);
            } catch (RuntimeException unavailable) {
                // Retain the deletion request for a later retry; never log memory keys or content.
                return;
            }
        }
    }
}
