package com.coffer.task.application;

import com.coffer.auth.service.TenantJobRunner;
import com.coffer.file.application.async.AsyncFileProcessor;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.task.domain.AsyncTaskStatus;
import com.coffer.task.infrastructure.persistence.AsyncTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Recover from durable owner-scoped intent, without a previous HTTP request or its ThreadLocal. */
@Component @RequiredArgsConstructor
public class UploadTaskRecovery {
    private final TenantJobRunner owners;
    private final AsyncTaskRepository tasks;
    private final FileMetadataRepository files;
    private final AsyncFileProcessor processor;

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        owners.runForEnabledOwners(owner -> {
            for (var task : tasks.findByStatusOrderByCreatedAtDesc(AsyncTaskStatus.PROCESSING)) {
                task.setStatus(AsyncTaskStatus.FAILED);
                task.setResult("服务重启中断了处理，请重试");
                tasks.save(task);
                files.findByTaskId(task.getTaskId()).ifPresent(file -> { file.markAsFailed(); files.save(file); });
            }
            for (var task : tasks.findByStatusOrderByCreatedAtDesc(AsyncTaskStatus.PENDING)) {
                if (files.findByTaskId(task.getTaskId()).isPresent()) processor.processFileAsync(task.getTaskId());
                else {
                    task.setStatus(AsyncTaskStatus.FAILED);
                    task.setResult("文件已不存在");
                    tasks.save(task);
                }
            }
        });
    }
}
