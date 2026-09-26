package com.coffer.file.application;

import com.coffer.file.domain.StorageDeletionStatus;
import com.coffer.file.domain.StorageDeletionTask;
import com.coffer.file.infrastructure.persistence.StorageDeletionTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Stores and advances deletion intent in short database transactions. */
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class StorageDeletionTaskService {

    private final StorageDeletionTaskRepository repository;

    @Transactional
    public void enqueue(Long fileId, String objectPath) {
        if (objectPath == null || objectPath.isBlank()) return;
        com.coffer.service.MinioStorageService.requireOwnedPath(objectPath);
        StorageDeletionTask task = new StorageDeletionTask();
        task.setTaskKey("file-delete:" + fileId + ":" + UUID.randomUUID());
        task.setFileId(fileId);
        task.setObjectPath(objectPath);
        task.setStatus(StorageDeletionStatus.PENDING);
        task.setAttempts(0);
        repository.save(task);
    }

    @Transactional(readOnly = true)
    public List<Long> dueIds() {
        return repository.findDue(StorageDeletionStatus.PENDING, StorageDeletionStatus.FAILED,
                        StorageDeletionStatus.RUNNING, LocalDateTime.now(), PageRequest.of(0, 20))
                .stream().map(StorageDeletionTask::getId).toList();
    }

    @Transactional
    public StorageDeletionTask claim(Long id) {
        StorageDeletionTask task = repository.findByIdForUpdate(id).orElse(null);
        if (task == null || task.getStatus() == StorageDeletionStatus.SUCCEEDED) return null;
        LocalDateTime now = LocalDateTime.now();
        if (task.getNextAttemptAt() != null && task.getNextAttemptAt().isAfter(now)) return null;
        task.setStatus(StorageDeletionStatus.RUNNING);
        task.setAttempts(task.getAttempts() + 1);
        task.setNextAttemptAt(now.plusMinutes(5)); // a crash leaves a reclaimable lease
        return repository.save(task);
    }

    @Transactional
    public void succeeded(Long id) {
        StorageDeletionTask task = repository.findByIdForUpdate(id).orElse(null);
        if (task == null) return;
        task.setStatus(StorageDeletionStatus.SUCCEEDED);
        task.setNextAttemptAt(null);
        task.setLastErrorCode(null);
        repository.save(task);
    }

    @Transactional
    public void failed(Long id, Exception failure) {
        StorageDeletionTask task = repository.findByIdForUpdate(id).orElse(null);
        if (task == null) return;
        task.setStatus(StorageDeletionStatus.FAILED);
        long delaySeconds = Math.min(3600L, 30L << Math.min(7, Math.max(0, task.getAttempts() - 1)));
        task.setNextAttemptAt(LocalDateTime.now().plusSeconds(delaySeconds));
        task.setLastErrorCode(failure.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", ""));
        repository.save(task);
    }
}
