package com.coffer.file.application;

import com.coffer.auth.service.TenantJobRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Replays committed rename intents only while their original file state still matches. */
@Component @RequiredArgsConstructor @Slf4j
public class FileRenameIntentRecovery {
    private final TenantJobRunner owners;
    private final FileRenameIntentService intents;

    @EventListener(ApplicationReadyEvent.class)
    public void atStartup() { owners.runForEnabledOwners(owner -> scan(true)); }

    @com.coffer.auth.service.OwnerScheduled
    @Scheduled(fixedDelayString = "${coffer.storage.rename-recovery-delay-ms:60000}")
    public void periodicScan() { scan(false); }

    private void scan(boolean startup) {
        for (String id : intents.dueIds(startup)) {
            try { intents.recoverOne(id); }
            catch (RuntimeException failure) {
                try { intents.recordFailure(id, failure); }
                catch (RuntimeException trackingFailure) {
                    log.error("重命名意图恢复失败且无法记录重试状态 id={} exceptionType={}",
                            id, trackingFailure.getClass().getSimpleName());
                }
                log.warn("重命名意图恢复稍后重试 id={} exceptionType={}", id, failure.getClass().getSimpleName());
            }
        }
    }
}
