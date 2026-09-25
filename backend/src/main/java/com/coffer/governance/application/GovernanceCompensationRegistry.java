package com.coffer.governance.application;

import com.coffer.governance.api.dto.GovernanceCompensationTaskResponse;
import com.coffer.governance.domain.*;
import com.coffer.governance.infrastructure.persistence.GovernanceCompensationTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GovernanceCompensationRegistry {
    private final GovernanceCompensationTaskRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized GovernanceCompensationTask register(String batchId, Long itemId,
                                                GovernanceCompensationAction action,
                                                String objectPath, String error) {
        String key = itemId + ":" + action;
        GovernanceCompensationTask task = repository.findByTaskKey(key).orElse(null);
        if (task == null) {
            task = GovernanceCompensationTask.builder().taskKey(key).batchId(batchId).itemId(itemId)
                    .action(action).objectPath(objectPath).lastError(error).build();
            return repository.saveAndFlush(task);
        }
        if (task.getStatus() != GovernanceCompensationStatus.SUCCEEDED) {
            task.setStatus(GovernanceCompensationStatus.PENDING);
            task.setObjectPath(objectPath);
            task.setLastError(error);
            task.setNextAttemptAt(null);
            task.setFinishedAt(null);
            return repository.save(task);
        }
        return task;
    }

    @Transactional
    public GovernanceCompensationTask claim(Long id) {
        GovernanceCompensationTask task = repository.findByIdForUpdate(id).orElse(null);
        if (task == null || task.getStatus() == GovernanceCompensationStatus.RUNNING
                || task.getStatus() == GovernanceCompensationStatus.SUCCEEDED) return null;
        task.setStatus(GovernanceCompensationStatus.RUNNING);
        task.setAttempts(task.getAttempts() + 1);
        task.setNextAttemptAt(null);
        return repository.save(task);
    }

    @Transactional
    public void succeeded(Long id) {
        GovernanceCompensationTask task = lock(id);
        task.setStatus(GovernanceCompensationStatus.SUCCEEDED);
        task.setLastError(null);
        task.setNextAttemptAt(null);
        task.setFinishedAt(LocalDateTime.now());
        repository.save(task);
    }

    @Transactional
    public void failed(Long id, String error) {
        GovernanceCompensationTask task = lock(id);
        task.setStatus(GovernanceCompensationStatus.FAILED);
        task.setLastError(error);
        long delay = Math.min(300, 5L * (1L << Math.min(task.getAttempts(), 6)));
        task.setNextAttemptAt(LocalDateTime.now().plusSeconds(delay));
        repository.save(task);
    }

    @Transactional
    public void retryBatch(String batchId) {
        List<GovernanceCompensationTask> tasks = repository.findByBatchIdOrderByCreatedAtDesc(batchId);
        if (tasks.isEmpty()) throw new IllegalArgumentException("该批次没有补偿任务");
        tasks.stream().filter(t -> t.getStatus() != GovernanceCompensationStatus.SUCCEEDED).forEach(t -> {
            t.setStatus(GovernanceCompensationStatus.PENDING);
            t.setNextAttemptAt(null);
            t.setFinishedAt(null);
        });
        repository.saveAll(tasks);
    }

    @Transactional
    public void recoverInterruptedTasks() {
        repository.findByStatus(GovernanceCompensationStatus.RUNNING).forEach(task -> {
            task.setStatus(GovernanceCompensationStatus.PENDING);
            task.setNextAttemptAt(null);
            task.setLastError("服务重启后恢复中断的补偿任务");
            repository.save(task);
        });
    }

    @Transactional(readOnly = true)
    public List<GovernanceCompensationTaskResponse> list(String batchId) {
        return repository.findByBatchIdOrderByCreatedAtDesc(batchId).stream().map(this::toResponse).toList();
    }

    private GovernanceCompensationTask lock(Long id) {
        return repository.findByIdForUpdate(id).orElseThrow(() -> new IllegalArgumentException("补偿任务不存在: " + id));
    }
    private GovernanceCompensationTaskResponse toResponse(GovernanceCompensationTask t) {
        return new GovernanceCompensationTaskResponse(t.getId(), t.getBatchId(), t.getItemId(), t.getAction(),
                t.getStatus(), t.getObjectPath(), t.getAttempts(), t.getLastError(), t.getNextAttemptAt(),
                t.getCreatedAt(), t.getUpdatedAt(), t.getFinishedAt());
    }
}
