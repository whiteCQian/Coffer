package com.coffer.governance.application;

import com.coffer.governance.api.dto.GovernanceCompensationTaskResponse;
import com.coffer.governance.domain.*;
import com.coffer.governance.infrastructure.persistence.GovernanceCompensationTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class GovernanceCompensationRegistry {
    private final GovernanceCompensationTaskRepository repository;
    @Value("${coffer.governance.archive.compensation-max-attempts:2}")
    private int maxAttempts;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized GovernanceCompensationTask register(String batchId, Long itemId,
                                                GovernanceCompensationAction action,
                                                String objectPath, String error) {
        com.coffer.file.storage.StorageKey.requireOwned(objectPath);
        String key = itemId + ":" + action;
        GovernanceCompensationTask task = repository.findByTaskKey(key).orElse(null);
        if (task == null) {
            task = GovernanceCompensationTask.builder().taskKey(key).batchId(batchId).itemId(itemId)
                    .action(action).objectPath(objectPath).lastError(error).build();
            return repository.saveAndFlush(task);
        }
        if (!Objects.equals(task.getBatchId(), batchId)
                || !Objects.equals(task.getItemId(), itemId)
                || task.getAction() != action
                || !Objects.equals(task.getObjectPath(), objectPath)) {
            throw new IllegalStateException("补偿任务身份与已有台账不一致");
        }
        // Recovery scans may register the same work repeatedly. They must not
        // bypass a backoff or silently release an item held for manual review.
        return task;
    }

    @Transactional
    public GovernanceCompensationTask claim(Long id) {
        GovernanceCompensationTask task = repository.findByIdForUpdate(id).orElse(null);
        if (task == null || task.getStatus() == GovernanceCompensationStatus.RUNNING
                || task.getStatus() == GovernanceCompensationStatus.SUCCEEDED
                || task.getStatus() == GovernanceCompensationStatus.MANUAL_REVIEW) return null;
        LocalDateTime now = LocalDateTime.now();
        if (task.getNextAttemptAt() != null && task.getNextAttemptAt().isAfter(now)) return null;
        if (task.getAttempts() >= Math.max(1, maxAttempts)) {
            task.setStatus(GovernanceCompensationStatus.MANUAL_REVIEW);
            task.setNextAttemptAt(null);
            task.setFinishedAt(now);
            repository.save(task);
            return null;
        }
        task.setStatus(GovernanceCompensationStatus.RUNNING);
        task.setAttempts(task.getAttempts() + 1);
        task.setNextAttemptAt(null);
        task.setFinishedAt(null);
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
    public void manualReview(Long id, String error) {
        GovernanceCompensationTask task = lock(id);
        task.setStatus(GovernanceCompensationStatus.MANUAL_REVIEW);
        task.setLastError(error);
        task.setNextAttemptAt(null);
        task.setFinishedAt(LocalDateTime.now());
        repository.save(task);
    }

    @Transactional
    public void failed(Long id, String error) {
        GovernanceCompensationTask task = lock(id);
        if (task.getStatus() == GovernanceCompensationStatus.SUCCEEDED
                || task.getStatus() == GovernanceCompensationStatus.MANUAL_REVIEW) return;
        boolean exhausted = task.getAttempts() >= Math.max(1, maxAttempts);
        task.setStatus(exhausted ? GovernanceCompensationStatus.MANUAL_REVIEW
                : GovernanceCompensationStatus.FAILED);
        task.setLastError(error);
        long delay = Math.min(300, 5L * (1L << Math.min(task.getAttempts(), 6)));
        task.setNextAttemptAt(exhausted ? null : LocalDateTime.now().plusSeconds(delay));
        task.setFinishedAt(exhausted ? LocalDateTime.now() : null);
        repository.save(task);
    }

    @Transactional
    public void retryBatch(String batchId) {
        List<GovernanceCompensationTask> tasks = repository.findByBatchIdOrderByCreatedAtDesc(batchId);
        if (tasks.isEmpty()) throw new IllegalArgumentException("该批次没有补偿任务");
        tasks.stream().filter(t -> t.getStatus() == GovernanceCompensationStatus.FAILED
                || t.getStatus() == GovernanceCompensationStatus.MANUAL_REVIEW).forEach(t -> {
            t.setStatus(GovernanceCompensationStatus.PENDING);
            t.setAttempts(0);
            t.setNextAttemptAt(null);
            t.setFinishedAt(null);
            t.setLastError(null);
        });
        repository.saveAll(tasks);
    }

    @Transactional
    public void recoverInterruptedTasks() {
        repository.findByStatus(GovernanceCompensationStatus.RUNNING).forEach(task -> {
            boolean exhausted = task.getAttempts() >= Math.max(1, maxAttempts);
            task.setStatus(exhausted ? GovernanceCompensationStatus.MANUAL_REVIEW
                    : GovernanceCompensationStatus.PENDING);
            task.setNextAttemptAt(null);
            task.setFinishedAt(exhausted ? LocalDateTime.now() : null);
            task.setLastError(exhausted ? "补偿任务多次中断，需人工核对后重试"
                    : "服务重启后恢复中断的补偿任务");
            repository.save(task);
        });
    }

    @Transactional(readOnly = true)
    public List<GovernanceCompensationTaskResponse> list(String batchId) {
        return repository.findByBatchIdOrderByCreatedAtDesc(batchId).stream().map(this::toResponse).toList();
    }

    private GovernanceCompensationTask lock(Long id) {
        return repository.findByIdForUpdate(id).orElseThrow(() -> new com.coffer.auth.service.ResourceNotFoundException());
    }
    private GovernanceCompensationTaskResponse toResponse(GovernanceCompensationTask t) {
        return new GovernanceCompensationTaskResponse(t.getId(), t.getBatchId(), t.getItemId(), t.getAction(),
                t.getStatus(), t.getObjectPath(), t.getAttempts(), t.getLastError(), t.getNextAttemptAt(),
                t.getCreatedAt(), t.getUpdatedAt(), t.getFinishedAt());
    }
}
