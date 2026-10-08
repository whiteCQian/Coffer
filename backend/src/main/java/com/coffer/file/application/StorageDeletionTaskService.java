package com.coffer.file.application;

import com.coffer.file.domain.StorageDeletionStatus;
import com.coffer.file.domain.StorageDeletionTask;
import com.coffer.file.infrastructure.persistence.StorageDeletionTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDateTime;
import java.util.List;

/** Stores and advances deletion intent in short database transactions. */
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class StorageDeletionTaskService {

    private final StorageDeletionTaskRepository repository;
    private final com.coffer.file.storage.FileStoragePort storage;

    @Value("${coffer.storage.deletion-max-attempts:2}")
    private int maxAttempts;
    @Value("${coffer.storage.deletion-retention-hours:24}")
    private long retentionHours;
    @Value("${coffer.storage.deletion-lease-seconds:300}")
    private long leaseSeconds;

    @Transactional
    public void enqueue(Long fileId, String objectPath, String contentSha256) {
        enqueueWithKey("file-delete:" + fileId, fileId, objectPath, contentSha256);
    }

    /** A replacement has its own cleanup key and must not consume the later file-deletion slot. */
    @Transactional
    public void enqueueReplacedVersion(String operationId, Long fileId,
                                       String objectPath, String contentSha256) {
        enqueueWithKey("work-save:" + operationId, fileId, objectPath, contentSha256);
    }

    private void enqueueWithKey(String taskKey, Long fileId, String objectPath, String contentSha256) {
        if (objectPath == null || objectPath.isBlank()) return;
        com.coffer.file.storage.StorageKey.requireOwned(objectPath);
        if (repository.findByTaskKey(taskKey).isPresent()) return;
        StorageDeletionTask task = new StorageDeletionTask();
        task.setTaskKey(taskKey);
        task.setFileId(fileId);
        task.setObjectPath(objectPath);
        task.setContentSha256(contentSha256);
        task.setStatus(contentSha256 == null ? StorageDeletionStatus.MANUAL_REVIEW : StorageDeletionStatus.PENDING);
        if (contentSha256 == null) task.setLastErrorCode("CONTENT_IDENTITY_UNKNOWN");
        task.setRetentionUntil(LocalDateTime.now().plusHours(Math.max(0, retentionHours)));
        if (contentSha256 != null) task.setNextAttemptAt(task.getRetentionUntil());
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
        if (task == null || task.getStatus() == StorageDeletionStatus.SUCCEEDED
                || task.getStatus() == StorageDeletionStatus.MANUAL_REVIEW) return null;
        if (task.getAttempts() >= Math.max(1, maxAttempts)) {
            task.setStatus(StorageDeletionStatus.MANUAL_REVIEW);
            task.setNextAttemptAt(null);
            repository.save(task);
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        if (task.getNextAttemptAt() != null && task.getNextAttemptAt().isAfter(now)) return null;
        task.setStatus(StorageDeletionStatus.RUNNING);
        task.setAttempts(task.getAttempts() + 1);
        task.setNextAttemptAt(now.plusSeconds(Math.max(1, leaseSeconds)));
        return repository.save(task);
    }

    @Transactional
    public void succeeded(Long id) {
        StorageDeletionTask task = repository.findByIdForUpdate(id).orElse(null);
        if (task == null) return;
        task.setStatus(StorageDeletionStatus.SUCCEEDED);
        task.setNextAttemptAt(null);
        task.setDeletedAt(LocalDateTime.now());
        task.setLastErrorCode(null);
        repository.save(task);
    }

    @Transactional
    public void failed(Long id, Exception failure) {
        StorageDeletionTask task = repository.findByIdForUpdate(id).orElse(null);
        if (task == null) return;
        task.setStatus(task.getAttempts() >= Math.max(1, maxAttempts)
                ? StorageDeletionStatus.MANUAL_REVIEW : StorageDeletionStatus.FAILED);
        long delaySeconds = Math.min(3600L, 30L << Math.min(7, Math.max(0, task.getAttempts() - 1)));
        task.setNextAttemptAt(task.getStatus() == StorageDeletionStatus.MANUAL_REVIEW
                ? null : LocalDateTime.now().plusSeconds(delaySeconds));
        task.setLastErrorCode(failure.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", ""));
        repository.save(task);
    }

    @Transactional(readOnly = true)
    public List<DeletionView> recent() { return recent(0); }

    @Transactional(readOnly = true)
    public List<DeletionView> recent(int page) {
        if (page < 0 || page > 10_000) throw new IllegalArgumentException("操作台账页码无效");
        return repository.findByOrderByCreatedAtDescIdDesc(PageRequest.of(page, 100)).stream()
                .map(task -> new DeletionView(task.getId(), task.getFileId(), task.getStatus(),
                        task.getAttempts(), task.getLastErrorCode(), task.getCreatedAt(),
                        task.getRetentionUntil(), task.getDeletedAt())).toList();
    }

    @Transactional
    public void retry(Long id) {
        StorageDeletionTask task = repository.findByIdForUpdate(id)
                .orElseThrow(com.coffer.auth.service.ResourceNotFoundException::new);
        if (task.getStatus() != StorageDeletionStatus.MANUAL_REVIEW
                || task.getContentSha256() == null) {
            throw new IllegalStateException("该清理任务需要人工核对对象身份");
        }
        task.setAttempts(0);
        task.setStatus(StorageDeletionStatus.PENDING);
        task.setNextAttemptAt(null);
        task.setLastErrorCode(null);
        repository.save(task);
    }

    /** Explicitly review a legacy object's digest before allowing physical deletion. */
    @Transactional
    public void confirmIdentity(Long id, String expectedSha256) {
        StorageDeletionTask task = repository.findByIdForUpdate(id)
                .orElseThrow(com.coffer.auth.service.ResourceNotFoundException::new);
        if (task.getStatus() != StorageDeletionStatus.MANUAL_REVIEW
                || expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("清理任务或 SHA-256 无效");
        var current = storage.stat(task.getObjectPath());
        if (!expectedSha256.equals(current.sha256()))
            throw new com.coffer.file.storage.StorageConflictException("对象摘要与人工确认值不一致");
        task.setContentSha256(expectedSha256);
        task.setStatus(StorageDeletionStatus.PENDING);
        task.setAttempts(0);
        task.setLastErrorCode(null);
        task.setNextAttemptAt(task.getRetentionUntil() != null && task.getRetentionUntil().isAfter(LocalDateTime.now())
                ? task.getRetentionUntil() : LocalDateTime.now());
        repository.save(task);
    }

    public record DeletionView(Long id, Long fileId, StorageDeletionStatus status,
                               int attempts, String errorCode, LocalDateTime createdAt,
                               LocalDateTime retentionUntil, LocalDateTime deletedAt) { }
}
