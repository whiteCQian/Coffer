package com.coffer.task.application;

import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.task.domain.AsyncTask;
import com.coffer.task.domain.AsyncTaskStatus;
import com.coffer.task.infrastructure.persistence.AsyncTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Claims a processing task before scheduling it, in a short durable transaction. */
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class AsyncTaskLeaseService {
    private final AsyncTaskRepository tasks;
    private final FileMetadataRepository files;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.coffer.web.WebLimits webLimits;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long claimPending(String taskId) {
        if (webLimits != null && !webLimits.canClaimTask()) return null;
        AsyncTask task = tasks.lockByTaskId(taskId).orElse(null);
        if (task == null || task.getStatus() != AsyncTaskStatus.PENDING) return null;
        if (files.findByTaskId(taskId).isEmpty()) {
            task.setStatus(AsyncTaskStatus.FAILED);
            task.setResult("文件元数据不存在");
            tasks.save(task);
            return null;
        }
        task.setStatus(AsyncTaskStatus.PROCESSING);
        task.setAttempts(task.getAttempts() + 1);
        task.setLeaseUntil(LocalDateTime.now().plusMinutes(30));
        tasks.saveAndFlush(task);
        return task.getOwnerId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failExpired(String taskId) {
        AsyncTask task = tasks.lockByTaskId(taskId).orElse(null);
        if (task == null || task.getStatus() != AsyncTaskStatus.PROCESSING
                || task.getLeaseUntil() != null && task.getLeaseUntil().isAfter(LocalDateTime.now())) return;
        task.setStatus(AsyncTaskStatus.FAILED);
        task.setLeaseUntil(null);
        task.setResult("文件处理被中断，请重试");
        tasks.save(task);
        files.findByTaskId(taskId).ifPresent(file -> { file.markAsFailed(); files.save(file); });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failedToDispatch(String taskId) {
        AsyncTask task = tasks.lockByTaskId(taskId).orElse(null);
        if (task == null || task.getStatus() != AsyncTaskStatus.PROCESSING) return;
        task.setStatus(AsyncTaskStatus.FAILED);
        task.setLeaseUntil(null);
        task.setResult("处理队列不可用，请重试");
        tasks.save(task);
        files.findByTaskId(taskId).ifPresent(file -> { file.markAsFailed(); files.save(file); });
    }
}
