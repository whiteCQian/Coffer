package com.coffer.file.application;

import com.coffer.file.application.event.FileUploadedEvent;
import com.coffer.file.domain.*;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.infrastructure.persistence.WorkSaveIntentRepository;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.file.storage.StorageConflictException;
import com.coffer.file.storage.StorageKey;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.task.domain.AsyncTask;
import com.coffer.task.domain.AsyncTaskStatus;
import com.coffer.task.infrastructure.persistence.AsyncTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Short committed steps around a non-transactional working-copy object write. */
@Service @RequiredArgsConstructor @com.coffer.auth.service.OwnerOnly
public class WorkSaveIntentService {
    private final WorkSaveIntentRepository intents;
    private final FileMetadataRepository files;
    private final AsyncTaskRepository tasks;
    private final FileTagMappingRepository tags;
    private final StorageDeletionTaskService deletions;
    private final FileStoragePort storage;
    private final ApplicationEventPublisher events;
    @org.springframework.beans.factory.annotation.Value("${coffer.storage.work-save-max-attempts:2}")
    private int maxAttempts;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void prepare(String id, FileMetadata file, long expectedRevision, String expectedSha256,
                        String targetKey, long targetSize, String requestSha256, String taskId,
                        String modelSnapshotId, com.coffer.governance.domain.GovernanceRunMode mode) {
        prepareInternal(id, file, expectedRevision, expectedSha256, targetKey, targetSize, requestSha256,
                taskId, modelSnapshotId, mode, false);
    }
    private void prepareInternal(String id, FileMetadata file, long expectedRevision, String expectedSha256,
                                 String targetKey, long targetSize, String requestSha256, String taskId,
                                 String modelSnapshotId, com.coffer.governance.domain.GovernanceRunMode mode, boolean preserve) {
        StorageKey.requireOwned(file.getStoragePath());
        StorageKey.requireOwned(targetKey);
        if (file.getId() == null || expectedRevision < 0 || targetSize < 0
                || file.getStatus() == FileStatus.PENDING || file.getStatus() == FileStatus.PROCESSING
                || !Objects.equals(file.getRevision(), expectedRevision)
                || expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")
                || !expectedSha256.equals(file.getContentSha256())
                || requestSha256 == null || !requestSha256.matches("[0-9a-f]{64}")
                || file.getStoragePath().equals(targetKey) || mode == null || modelSnapshotId == null)
            throw new StorageConflictException("工作副本与正式文件身份不一致");
        if (intents.existsById(id)) throw new StorageConflictException("保存意图已存在");
        WorkSaveIntent intent = new WorkSaveIntent();
        intent.setId(id);
        intent.setFileId(file.getId());
        intent.setExpectedRevision(expectedRevision);
        intent.setBeforeKey(file.getStoragePath());
        intent.setBeforeSha256(expectedSha256);
        intent.setBeforeSize(file.getFileSize());
        intent.setPreserveOnly(preserve);
        var localIdentity = preserve ? null : storage.localIdentity(file.getStoragePath());
        if (localIdentity != null) {
            intent.setBeforeModifiedTime(localIdentity.modifiedTime());
            intent.setBeforeFileKey(localIdentity.fileKey());
            intent.setBeforeSize(localIdentity.size());
        }
        intent.setTargetKey(targetKey);
        intent.setTargetSize(targetSize);
        intent.setRequestSha256(requestSha256);
        intent.setTaskId(taskId);
        intent.setFileName(file.getFileName());
        intent.setFileType(file.getFileType());
        intent.setModelSnapshotId(modelSnapshotId);
        intent.setRunMode(mode);
        intent.setStatus(WorkSaveStatus.PREPARED);
        intent.setNextAttemptAt(LocalDateTime.now().plusMinutes(30));
        intents.saveAndFlush(intent);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void objectWritten(String id, FileStoragePort.StoredObject object) {
        WorkSaveIntent intent = lock(id);
        if (intent.getStatus() == WorkSaveStatus.COMMITTED) return;
        if (intent.getStatus() != WorkSaveStatus.PREPARED && intent.getStatus() != WorkSaveStatus.OBJECT_WRITTEN)
            throw new StorageConflictException("工作副本保存状态不允许登记正文");
        if (!Objects.equals(intent.getTargetKey(), object.key())
                || intent.getTargetSize() != object.size()
                || !Objects.equals(intent.getRequestSha256(), object.sha256()))
            throw new StorageConflictException("工作副本正文与保存意图不一致");
        intent.setTargetSha256(object.sha256());
        intent.setTargetEtag(object.etag());
        intent.setStatus(WorkSaveStatus.OBJECT_WRITTEN);
        intent.setNextAttemptAt(null);
        intent.setLastErrorCode(null);
        intents.save(intent);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void preparePreserve(String id, Long fileId, String fileName, String fileType, String beforeKey,
                                long revision, String sha, long beforeSize, String target, long size, String requestSha, String task) {
        var file = FileMetadata.builder().fileName(fileName).fileType(fileType).storagePath(beforeKey)
                .revision(revision).contentSha256(sha).fileSize(beforeSize).status(FileStatus.COMPLETED).build();
        file.setId(fileId);
        prepareInternal(id, file, revision, sha, target, size, requestSha, task, "work-copy-save-as",
                com.coffer.governance.domain.GovernanceRunMode.LOCAL, true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean verifyLocalBaseline(String id, FileStoragePort.LocalIdentity expected) {
        var intent = lock(id);
        if (expected != null && (!Objects.equals(intent.getBeforeSize(), expected.size())
                || !Objects.equals(intent.getBeforeModifiedTime(), expected.modifiedTime())
                || !Objects.equals(intent.getBeforeFileKey(), expected.fileKey()))) {
            intent.setStatus(WorkSaveStatus.CONFLICTED); intent.setLastErrorCode("FORMAL_FILE_CHANGED"); intents.save(intent); return false;
        }
        return true;
    }

    /** Returns false after a durable conflict; the new bytes stay available for manual recovery. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean commit(String id) {
        WorkSaveIntent intent = lock(id);
        if (intent.getStatus() == WorkSaveStatus.COMMITTED) return true;
        if (intent.getStatus() != WorkSaveStatus.OBJECT_WRITTEN) return false;
        if (intent.isPreserveOnly()) {
            intent.setStatus(WorkSaveStatus.MANUAL_REVIEW); intent.setLastErrorCode("SAVE_AS_REQUESTED"); intents.save(intent); return false;
        }
        FileMetadata file = files.findByIdForUpdate(intent.getFileId()).orElse(null);
        if (file == null || !Objects.equals(file.getRevision(), intent.getExpectedRevision())
                || file.getStatus() == FileStatus.PENDING || file.getStatus() == FileStatus.PROCESSING
                || !Objects.equals(file.getStoragePath(), intent.getBeforeKey())
                || !Objects.equals(file.getContentSha256(), intent.getBeforeSha256())
                || !Objects.equals(file.getFileName(), intent.getFileName())) {
            intent.setStatus(WorkSaveStatus.CONFLICTED);
            intent.setLastErrorCode("FORMAL_FILE_CHANGED");
            intents.save(intent);
            return false;
        }
        FileStoragePort.StoredObject old = storage.stat(intent.getBeforeKey());
        FileStoragePort.StoredObject replacement = storage.stat(intent.getTargetKey());
        var localIdentity = storage.localIdentity(intent.getBeforeKey());
        if (!intent.getBeforeSha256().equals(old.sha256())
                || !Objects.equals(file.getFileSize(), old.size())
                || intent.getBeforeSize() != null && intent.getBeforeSize() != old.size()
                || intent.getBeforeModifiedTime() != null && (localIdentity == null
                    || !intent.getBeforeModifiedTime().equals(localIdentity.modifiedTime())
                    || !Objects.equals(intent.getBeforeFileKey(), localIdentity.fileKey()))
                || !Objects.equals(intent.getTargetSha256(), replacement.sha256())
                || intent.getTargetSize() != replacement.size()) {
            intent.setStatus(WorkSaveStatus.MANUAL_REVIEW);
            intent.setLastErrorCode("OBJECT_IDENTITY_CONFLICT");
            intents.save(intent);
            return false;
        }
        String priorTaskId = file.getTaskId();
        tags.deleteByFileId(file.getId(), com.coffer.auth.service.TenantContext.requireOwnerId());
        if (priorTaskId != null) tasks.findByTaskId(priorTaskId).ifPresent(tasks::delete);
        tasks.save(AsyncTask.builder().taskId(intent.getTaskId()).fileName(file.getFileName())
                .runMode(intent.getRunMode()).modelSnapshotId(intent.getModelSnapshotId())
                .status(AsyncTaskStatus.PENDING).progress(0).build());
        file.setStoragePath(intent.getTargetKey());
        file.setContentSha256(replacement.sha256());
        file.setContentEtag(replacement.etag());
        file.setFileSize(replacement.size());
        file.setRevision(intent.getExpectedRevision() + 1);
        file.setStatus(FileStatus.PENDING);
        file.setSummary(null);
        file.setCategory(CategoryType.OTHER);
        file.setArchived(false);
        file.setVectorIndexedAt(null);
        file.setVectorIndexGeneration(null);
        file.setTaskId(intent.getTaskId());
        file.setModelSnapshotId(intent.getModelSnapshotId());
        files.saveAndFlush(file);
        deletions.enqueueReplacedVersion(id, file.getId(), intent.getBeforeKey(), intent.getBeforeSha256());
        intent.setStatus(WorkSaveStatus.COMMITTED);
        intent.setLastErrorCode(null);
        intent.setNextAttemptAt(null);
        intents.save(intent);
        events.publishEvent(new FileUploadedEvent(intent.getTaskId()));
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void aborted(String id) {
        WorkSaveIntent intent = lock(id);
        if (intent.getStatus() != WorkSaveStatus.PREPARED) return;
        intent.setStatus(WorkSaveStatus.ABORTED);
        intent.setLastErrorCode("WRITE_NOT_PUBLISHED");
        intent.setNextAttemptAt(null);
        intents.save(intent);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void manual(String id, String code) {
        WorkSaveIntent intent = lock(id);
        if (intent.getStatus() == WorkSaveStatus.COMMITTED || intent.getStatus() == WorkSaveStatus.RECOVERED) return;
        intent.setStatus(WorkSaveStatus.MANUAL_REVIEW);
        intent.setLastErrorCode(code);
        intent.setNextAttemptAt(null);
        intents.save(intent);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(String id, RuntimeException error) {
        WorkSaveIntent intent = lock(id);
        if (intent.getStatus() == WorkSaveStatus.COMMITTED || intent.getStatus() == WorkSaveStatus.RECOVERED
                || intent.getStatus() == WorkSaveStatus.CONFLICTED
                || intent.getStatus() == WorkSaveStatus.MANUAL_REVIEW
                || intent.getStatus() == WorkSaveStatus.ABORTED) return;
        intent.setAttempts(intent.getAttempts() + 1);
        if (intent.getAttempts() >= Math.max(1, maxAttempts)) {
            intent.setStatus(WorkSaveStatus.MANUAL_REVIEW);
            intent.setNextAttemptAt(null);
        } else {
            intent.setNextAttemptAt(LocalDateTime.now().plusSeconds(Math.min(3600L,
                    30L << Math.min(7, intent.getAttempts() - 1))));
        }
        intent.setLastErrorCode(error.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", ""));
        intents.save(intent);
    }

    /** Preserve a conflicted edited copy as a separate failed file, without any model call. */
    @Transactional
    public Long restoreAsNewFile(String id) {
        WorkSaveIntent intent = lock(id);
        if (intent.getStatus() == WorkSaveStatus.RECOVERED) return intent.getRecoveredFileId();
        if (intent.getStatus() != WorkSaveStatus.CONFLICTED
                && intent.getStatus() != WorkSaveStatus.MANUAL_REVIEW)
            throw new IllegalStateException("该工作副本不处于可恢复状态");
        FileStoragePort.StoredObject object = storage.stat(intent.getTargetKey());
        if (intent.getTargetSha256() == null || !intent.getTargetSha256().equals(object.sha256())
                || intent.getTargetSize() != object.size()
                || files.existsByStoragePath(intent.getTargetKey()))
            throw new StorageConflictException("工作副本正文身份不一致");
        FileMetadata recovered = FileMetadata.builder().fileName(intent.getFileName())
                .fileType(intent.getFileType()).fileSize(object.size())
                .storagePath(intent.getTargetKey()).contentSha256(object.sha256())
                .contentEtag(object.etag()).taskId(intent.getTaskId())
                .status(FileStatus.FAILED).build();
        files.saveAndFlush(recovered);
        tasks.save(AsyncTask.builder().taskId(intent.getTaskId()).fileName(intent.getFileName())
                .status(AsyncTaskStatus.FAILED).result("工作副本已保留，请确认后重新分析")
                .runMode(intent.getRunMode()).build());
        intent.setRecoveredFileId(recovered.getId());
        intent.setStatus(WorkSaveStatus.RECOVERED);
        intent.setLastErrorCode("RECOVERED_NO_ANALYSIS");
        intents.save(intent);
        return recovered.getId();
    }

    @Transactional(readOnly = true)
    public WorkSaveIntent require(String id) {
        return intents.findById(id).orElseThrow(com.coffer.auth.service.ResourceNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<WorkSaveIntent> existing(String id) { return intents.findById(id); }

    @Transactional(readOnly = true)
    public List<String> dueIds(boolean startup) {
        List<WorkSaveStatus> states = List.of(WorkSaveStatus.PREPARED, WorkSaveStatus.OBJECT_WRITTEN);
        return (startup ? intents.findReadyAtStartup(states, LocalDateTime.now(), PageRequest.of(0, 50))
                : intents.findDue(states, LocalDateTime.now(), PageRequest.of(0, 50)))
                .stream().map(WorkSaveIntent::getId).toList();
    }

    @Transactional(readOnly = true)
    public List<View> recent() { return recent(0); }

    @Transactional(readOnly = true)
    public List<View> recent(int page) {
        if (page < 0 || page > 10_000) throw new IllegalArgumentException("操作台账页码无效");
        return intents.findByOrderByCreatedAtDescIdDesc(PageRequest.of(page, 100)).stream()
                .map(i -> new View(i.getId(), i.getFileId(), i.getFileName(), i.getStatus(),
                        i.getLastErrorCode(), i.getAttempts(), i.getExpectedRevision(),
                        i.getRecoveredFileId(), i.getCreatedAt())).toList();
    }
    public record View(String id, Long fileId, String fileName, WorkSaveStatus status,
                       String errorCode, int attempts, long expectedRevision,
                       Long recoveredFileId, LocalDateTime createdAt) { }

    private WorkSaveIntent lock(String id) {
        return intents.lockById(id).orElseThrow(com.coffer.auth.service.ResourceNotFoundException::new);
    }
}
