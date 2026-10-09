package com.coffer.governance.application;

import com.coffer.auth.service.TenantContext;
import org.springframework.stereotype.Component;
import java.util.concurrent.locks.ReentrantLock;

/** Serialize forward execution, rollback and cleanup of the same owner's file inside one process. */
@Component
public class GovernanceFileCoordinator {
    private final ReentrantLock[] locks = java.util.stream.IntStream.range(0, 256)
            .mapToObj(i -> new ReentrantLock()).toArray(ReentrantLock[]::new);
    public void run(Long fileId, Runnable action) {
        if (fileId == null) throw new IllegalArgumentException("治理台账缺少文件身份");
        long owner = TenantContext.requireOwnerId();
        ReentrantLock lock = locks[Math.floorMod((owner + ":" + fileId).hashCode(), locks.length)];
        lock.lock();
        try { action.run(); } finally { lock.unlock(); }
    }
}
