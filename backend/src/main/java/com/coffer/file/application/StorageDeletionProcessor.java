package com.coffer.file.application;

import com.coffer.file.domain.StorageDeletionTask;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.file.storage.StorageObjectNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StorageDeletionProcessor {

    private final StorageDeletionTaskService tasks;
    private final FileStoragePort storage;
    private final com.coffer.auth.service.TenantJobRunner owners;

    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void atStartup() {
        owners.runForEnabledOwners(ownerId -> processDue());
    }

    @com.coffer.auth.service.OwnerScheduled
    @Scheduled(fixedDelayString = "${coffer.storage.deletion-scan-delay-ms:30000}")
    public void processDue() {
        for (Long id : tasks.dueIds()) process(id);
    }

    public void process(Long id) {
        StorageDeletionTask task = tasks.claim(id);
        if (task == null) return;
        try {
            storage.delete(task.getObjectPath(), task.getContentSha256());
            tasks.succeeded(id);
        } catch (StorageObjectNotFoundException alreadyRemoved) {
            tasks.succeeded(id);
        } catch (Exception failure) {
            tasks.failed(id, failure);
            log.warn("持久化文件清理稍后重试 taskId={} exceptionType={}", id,
                    failure.getClass().getSimpleName());
        }
    }
}
