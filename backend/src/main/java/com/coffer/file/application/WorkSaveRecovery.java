package com.coffer.file.application;

import com.coffer.auth.service.TenantJobRunner;
import com.coffer.file.domain.WorkSaveStatus;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.file.storage.StorageObjectNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Replays edited-copy publication after a process exit or a lost after-commit event. */
@Component @RequiredArgsConstructor @Slf4j
public class WorkSaveRecovery {
    private final TenantJobRunner owners;
    private final WorkSaveIntentService intents;
    private final FileStoragePort storage;

    @EventListener(ApplicationReadyEvent.class)
    @org.springframework.core.annotation.Order(10)
    public void onStartup() { owners.runForEnabledOwners(owner -> scan(true)); }

    @com.coffer.auth.service.OwnerScheduled
    @Scheduled(fixedDelayString = "${coffer.storage.work-save-recovery-delay-ms:30000}")
    public void scan() { scan(false); }

    private void scan(boolean startup) {
        for (String id : intents.dueIds(startup)) {
            try { reconcile(id, startup); }
            catch (RuntimeException failure) {
                try { intents.failure(id, failure); }
                catch (RuntimeException tracking) {
                    log.error("工作副本对账状态保存失败 operationId={} type={}", id,
                            tracking.getClass().getSimpleName());
                }
                log.warn("工作副本对账待重试 operationId={} type={}", id,
                        failure.getClass().getSimpleName());
            }
        }
    }

    public void reconcile(String id) {
        reconcile(id, false);
    }

    void reconcile(String id, boolean startup) {
        var intent = intents.require(id);
        if (intent.getStatus() != WorkSaveStatus.PREPARED
                && intent.getStatus() != WorkSaveStatus.OBJECT_WRITTEN) return;
        if ((!startup || intent.getAttempts() > 0) && intent.getNextAttemptAt() != null
                && intent.getNextAttemptAt().isAfter(java.time.LocalDateTime.now())) return;
        FileStoragePort.StoredObject object;
        try { object = storage.stat(intent.getTargetKey()); }
        catch (StorageObjectNotFoundException missing) {
            if (intent.getStatus() == WorkSaveStatus.PREPARED) intents.aborted(id);
            else intents.manual(id, "MISSING_TARGET");
            return;
        }
        if (object.size() != intent.getTargetSize()
                || !intent.getRequestSha256().equals(object.sha256())
                || intent.getTargetSha256() != null
                    && !intent.getTargetSha256().equals(object.sha256())) {
            intents.manual(id, "OBJECT_IDENTITY_CONFLICT");
            return;
        }
        intents.objectWritten(id, object);
        if (intent.isPreserveOnly()) {
            intents.manual(id, "SAVE_AS_REQUESTED"); intents.restoreAsNewFile(id);
        } else intents.commit(id);
    }
}
