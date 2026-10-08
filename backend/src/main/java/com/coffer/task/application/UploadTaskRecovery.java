package com.coffer.task.application;

import com.coffer.auth.service.TenantJobRunner;
import com.coffer.file.application.async.AsyncFileProcessor;
import com.coffer.task.domain.AsyncTaskStatus;
import com.coffer.task.infrastructure.persistence.AsyncTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.domain.PageRequest;
import java.time.LocalDateTime;

/** Recover from durable owner-scoped intent, without a previous HTTP request or its ThreadLocal. */
@Component @RequiredArgsConstructor
public class UploadTaskRecovery {
    private final TenantJobRunner owners;
    private final AsyncTaskRepository tasks;
    private final AsyncFileProcessor processor;
    private final AsyncTaskLeaseService leases;

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        owners.runForEnabledOwners(owner -> scan());
    }

    @com.coffer.auth.service.OwnerScheduled
    @Scheduled(fixedDelayString = "${coffer.storage.upload-recovery-delay-ms:30000}")
    public void scanScheduled() { scan(); }

    private void scan() {
        // Bound each pass so a large restart backlog cannot fill the executor queue
        // and turn otherwise recoverable pending work into dispatch failures.
        for (var task : tasks.findExpired(AsyncTaskStatus.PROCESSING,
                LocalDateTime.now(), PageRequest.of(0, 50))) {
            leases.failExpired(task.getTaskId());
        }
        for (var task : tasks.findByStatusOrderByCreatedAtAsc(AsyncTaskStatus.PENDING,
                PageRequest.of(0, 50))) {
            processor.processFileAsync(task.getTaskId());
        }
    }
}
