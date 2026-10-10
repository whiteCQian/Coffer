package com.coffer.file.application;

import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileWriteIntent;
import com.coffer.file.domain.FileWriteIntentStatus;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.infrastructure.persistence.FileWriteIntentRepository;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.file.storage.StorageKey;
import com.coffer.task.domain.AsyncTaskStatus;
import com.coffer.task.domain.AsyncTask;
import com.coffer.file.domain.FileStatus;
import com.coffer.task.infrastructure.persistence.AsyncTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Small independent commits on either side of non-transactional object I/O. */
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class FileWriteIntentService {
    static final List<FileWriteIntentStatus> ACTIVE_KEY_STATES = List.of(
            FileWriteIntentStatus.PREPARED, FileWriteIntentStatus.OBJECT_WRITTEN,
            FileWriteIntentStatus.FAILED, FileWriteIntentStatus.MANUAL_REVIEW,
            FileWriteIntentStatus.DISCARD_PENDING, FileWriteIntentStatus.DISCARDING);
    private final FileWriteIntentRepository intents;
    private final FileMetadataRepository files;
    private final AsyncTaskRepository tasks;
    private final com.coffer.auth.infrastructure.AppUserRepository owners;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.coffer.web.WebLimits webLimits;
    @org.springframework.beans.factory.annotation.Value("${coffer.storage.write-recovery-max-attempts:2}")
    private int maxRecoveryAttempts;
    @org.springframework.beans.factory.annotation.Value("${coffer.storage.orphan-retention-hours:24}")
    private long orphanRetentionHours;
    @org.springframework.beans.factory.annotation.Value("${coffer.storage.orphan-discard-max-attempts:2}")
    private int maxDiscardAttempts;
    @org.springframework.beans.factory.annotation.Value("${coffer.storage.orphan-discard-lease-seconds:300}")
    private long discardLeaseSeconds;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void begin(String id, String taskId, String kind, String key,
                      String fileName, String fileType, String contentType, long size,
                      String modelSnapshotId) {
        StorageKey.requireOwned(key);
        if (size < 0 || fileName == null || fileName.isBlank()
                || !("UPLOAD".equals(kind) || "IMPORT".equals(kind))) {
            throw new IllegalArgumentException("上传意图无效");
        }
        if (webLimits != null) webLimits.admitTask(taskId);
        lockOwnerForKeyReservation();
        if (intents.existsById(id) || files.existsByStoragePath(key)
                || intents.existsByObjectKeyAndStatusIn(key, ACTIVE_KEY_STATES))
            throw new IllegalStateException("上传意图或对象路径已存在");
        FileWriteIntent intent = new FileWriteIntent();
        intent.setId(id);
        intent.setTaskId(taskId);
        intent.setKind(kind);
        intent.setObjectKey(key);
        intent.setFileName(fileName);
        intent.setFileType(fileType);
        intent.setContentType(contentType);
        intent.setDeclaredSize(size);
        intent.setModelSnapshotId(modelSnapshotId);
        intent.setStatus(FileWriteIntentStatus.PREPARED);
        intent.setLeaseUntil(LocalDateTime.now().plusMinutes(5));
        intents.saveAndFlush(intent);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void objectWritten(String id, FileStoragePort.StoredObject object) {
        FileWriteIntent intent = lock(id);
        if (!Objects.equals(intent.getObjectKey(), object.key())
                || intent.getDeclaredSize() != object.size()
                || object.sha256() == null || !object.sha256().matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("已写对象与上传意图不一致");
        }
        if (intent.getStatus() == FileWriteIntentStatus.REGISTERED) return;
        intent.setContentSha256(object.sha256());
        intent.setStatus(FileWriteIntentStatus.OBJECT_WRITTEN);
        intent.setLeaseUntil(LocalDateTime.now().plusMinutes(5));
        intent.setLastErrorCode(null);
        intents.save(intent);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registered(String id) {
        FileWriteIntent intent = lock(id);
        FileMetadata file = files.findByTaskId(intent.getTaskId())
                .orElseThrow(() -> new IllegalStateException("文件元数据尚未提交"));
        if (!Objects.equals(file.getStoragePath(), intent.getObjectKey())
                || !Objects.equals(file.getContentSha256(), intent.getContentSha256())
                || !Objects.equals(file.getFileSize(), intent.getDeclaredSize())) {
            throw new IllegalStateException("文件元数据与上传意图不一致");
        }
        intent.setFileId(file.getId());
        intent.setStatus(FileWriteIntentStatus.REGISTERED);
        intent.setLeaseUntil(null);
        intent.setNextAttemptAt(null);
        intent.setLastErrorCode(null);
        intents.save(intent);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(String id, String errorCode) {
        FileWriteIntent intent = lock(id);
        if (intent.getStatus() == FileWriteIntentStatus.REGISTERED) return;
        intent.setStatus(FileWriteIntentStatus.FAILED);
        intent.setLeaseUntil(null);
        intent.setNextAttemptAt(LocalDateTime.now());
        intent.setLastErrorCode(cleanCode(errorCode));
        intents.save(intent);
    }

    /** A transient reconciliation failure must remain visible and eventually stop retrying. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recoveryFailed(String id, RuntimeException failure) {
        FileWriteIntent intent = lock(id);
        if (intent.getStatus() == FileWriteIntentStatus.REGISTERED
                || intent.getStatus() == FileWriteIntentStatus.ABORTED
                || intent.getStatus() == FileWriteIntentStatus.MANUAL_REVIEW) return;
        intent.setAttempts(intent.getAttempts() + 1);
        intent.setStatus(intent.getAttempts() >= Math.max(1, maxRecoveryAttempts)
                ? FileWriteIntentStatus.MANUAL_REVIEW : FileWriteIntentStatus.FAILED);
        intent.setLeaseUntil(null);
        intent.setNextAttemptAt(intent.getStatus() == FileWriteIntentStatus.MANUAL_REVIEW
                ? null : LocalDateTime.now().plusSeconds(Math.min(3600L,
                        30L << Math.min(7, intent.getAttempts() - 1))));
        intent.setLastErrorCode(cleanCode(failure.getClass().getSimpleName()));
        intents.save(intent);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void manual(String id, String errorCode, String sha256) {
        FileWriteIntent intent = lock(id);
        if (intent.getStatus() == FileWriteIntentStatus.REGISTERED
                || intent.getStatus() == FileWriteIntentStatus.DISCARD_PENDING
                || intent.getStatus() == FileWriteIntentStatus.DISCARDING
                || intent.getStatus() == FileWriteIntentStatus.DISCARDED) return;
        if (sha256 != null) intent.setContentSha256(sha256);
        intent.setStatus(FileWriteIntentStatus.MANUAL_REVIEW);
        intent.setLeaseUntil(null);
        intent.setNextAttemptAt(null);
        intent.setLastErrorCode(cleanCode(errorCode));
        if ("ORPHAN_OBJECT".equals(errorCode) && intent.getRetentionUntil() == null)
            intent.setRetentionUntil(LocalDateTime.now().plusHours(Math.max(0, orphanRetentionHours)));
        intents.save(intent);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void aborted(String id, String errorCode) {
        FileWriteIntent intent = lock(id);
        if (intent.getStatus() == FileWriteIntentStatus.REGISTERED) return;
        intent.setStatus(FileWriteIntentStatus.ABORTED);
        intent.setLeaseUntil(null);
        intent.setNextAttemptAt(null);
        intent.setLastErrorCode(cleanCode(errorCode));
        intents.save(intent);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void missingContent(String id) {
        FileWriteIntent intent = lock(id);
        files.findByTaskId(intent.getTaskId()).ifPresent(file -> {
            file.markAsFailed();
            files.save(file);
        });
        tasks.findByTaskId(intent.getTaskId()).ifPresent(task -> {
            if (task.getStatus() != AsyncTaskStatus.COMPLETED) {
                task.setStatus(AsyncTaskStatus.FAILED);
                task.setResult("文件正文缺失，需人工恢复");
                tasks.save(task);
            }
        });
        intent.setStatus(FileWriteIntentStatus.MANUAL_REVIEW);
        intent.setLeaseUntil(null);
        intent.setNextAttemptAt(null);
        intent.setLastErrorCode("MISSING_CONTENT");
        intents.save(intent);
    }

    /** Reviewed recovery exposes the preserved original without sending it to a model. */
    @Transactional
    public Long attachForManualReview(String id, FileStoragePort.StoredObject object) {
        FileWriteIntent intent = lock(id);
        if (intent.getStatus() != FileWriteIntentStatus.MANUAL_REVIEW
                || !"ORPHAN_OBJECT".equals(intent.getLastErrorCode())
                || files.findByTaskId(intent.getTaskId()).isPresent()
                || !Objects.equals(intent.getObjectKey(), object.key())
                || intent.getDeclaredSize() != object.size()
                || !Objects.equals(intent.getContentSha256(), object.sha256())) {
            throw new IllegalStateException("上传意图不处于可恢复状态");
        }
        FileMetadata file = new FileMetadata();
        file.setFileName(intent.getFileName());
        file.setFileType(intent.getFileType());
        file.setFileSize(object.size());
        file.setStoragePath(intent.getObjectKey());
        file.setContentSha256(object.sha256());
        file.setContentEtag(object.etag());
        file.setTaskId(intent.getTaskId());
        file.setStatus(FileStatus.FAILED);
        file.setModelSnapshotId(null);
        files.saveAndFlush(file);
        AsyncTask task = AsyncTask.builder().taskId(intent.getTaskId())
                .fileName(intent.getFileName()).status(AsyncTaskStatus.FAILED)
                .result("文件已恢复；重新授权后可重试分析").build();
        tasks.save(task);
        intent.setFileId(file.getId());
        intent.setStatus(FileWriteIntentStatus.REGISTERED);
        intent.setLastErrorCode("RECOVERED_NO_ANALYSIS");
        intents.save(intent);
        return file.getId();
    }

    /** Inventory discovery is recorded before an orphan can be reviewed or attached. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUnknownOrphan(FileStoragePort.StoredObject object) {
        StorageKey.requireOwned(object.key());
        lockOwnerForKeyReservation();
        if (intents.existsByObjectKeyAndStatusIn(object.key(), ACTIVE_KEY_STATES)
                || files.existsByStoragePath(object.key())) return;
        if (object.sha256() == null || !object.sha256().matches("[0-9a-f]{64}"))
            throw new IllegalStateException("孤儿对象缺少可靠内容摘要");
        String name = object.key().substring(object.key().lastIndexOf('/') + 1);
        String type = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT) : null;
        FileWriteIntent intent = new FileWriteIntent();
        intent.setId(UUID.randomUUID().toString());
        intent.setTaskId(UUID.randomUUID().toString());
        intent.setKind("ORPHAN");
        intent.setObjectKey(object.key());
        intent.setFileName(name);
        intent.setFileType(type);
        intent.setDeclaredSize(object.size());
        intent.setContentSha256(object.sha256());
        intent.setStatus(FileWriteIntentStatus.MANUAL_REVIEW);
        intent.setLastErrorCode("ORPHAN_OBJECT");
        intent.setRetentionUntil(LocalDateTime.now().plusHours(Math.max(0, orphanRetentionHours)));
        intents.saveAndFlush(intent);
    }

    /** Preserve hidden MinIO version history as an owner-visible manual operation. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordVersionIssue(FileStoragePort.VersionIssue issue) {
        StorageKey.requireOwned(issue.key());
        if (!java.util.Set.of("DELETE_MARKER", "MULTIPLE_VERSIONS", "UNVERSIONED_OBJECT")
                .contains(issue.code())) throw new IllegalArgumentException("版本异常代码无效");
        lockOwnerForKeyReservation();
        var existing = intents.findFirstByObjectKeyAndKindAndStatus(
                issue.key(), "VERSION_ANOMALY", FileWriteIntentStatus.MANUAL_REVIEW);
        if (existing.isPresent()) {
            FileWriteIntent row = existing.orElseThrow();
            if (!Objects.equals(row.getLastErrorCode(), issue.code())) {
                row.setLastErrorCode(issue.code());
                intents.save(row);
            }
            return;
        }
        FileWriteIntent row = new FileWriteIntent();
        row.setId(UUID.randomUUID().toString());
        row.setTaskId(UUID.randomUUID().toString());
        row.setKind("VERSION_ANOMALY");
        row.setObjectKey(issue.key());
        row.setFileName(issue.key().substring(issue.key().lastIndexOf('/') + 1));
        row.setDeclaredSize(0);
        row.setStatus(FileWriteIntentStatus.MANUAL_REVIEW);
        row.setLastErrorCode(issue.code());
        intents.saveAndFlush(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveVersionIssue(String id) {
        FileWriteIntent row = lock(id);
        if (!"VERSION_ANOMALY".equals(row.getKind()))
            throw new IllegalStateException("版本异常不处于人工核对状态");
        if (row.getStatus() == FileWriteIntentStatus.RESOLVED) return;
        if (row.getStatus() != FileWriteIntentStatus.MANUAL_REVIEW)
            throw new IllegalStateException("版本异常不处于人工核对状态");
        row.setStatus(FileWriteIntentStatus.RESOLVED);
        row.setLastErrorCode("VERSION_ANOMALY_CLEARED");
        intents.save(row);
    }

    /** A confirmed orphan stays private until its retention window has elapsed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requestDiscard(String id, FileStoragePort.StoredObject object, String confirmedSha256) {
        FileWriteIntent intent = lock(id);
        if (intent.getStatus() != FileWriteIntentStatus.MANUAL_REVIEW
                || !"ORPHAN_OBJECT".equals(intent.getLastErrorCode())
                || confirmedSha256 == null || !confirmedSha256.matches("[0-9a-f]{64}")
                || !Objects.equals(confirmedSha256, intent.getContentSha256())
                || !Objects.equals(confirmedSha256, object.sha256())
                || !Objects.equals(intent.getObjectKey(), object.key())
                || intent.getDeclaredSize() != object.size()
                || files.existsByStoragePath(intent.getObjectKey()))
            throw new IllegalStateException("孤儿对象状态或身份与人工确认值不一致");
        if (intent.getRetentionUntil() == null)
            intent.setRetentionUntil(LocalDateTime.now().plusHours(Math.max(0, orphanRetentionHours)));
        intent.setStatus(FileWriteIntentStatus.DISCARD_PENDING);
        intent.setAttempts(0);
        intent.setNextAttemptAt(intent.getRetentionUntil());
        intent.setLeaseUntil(null);
        intent.setLastErrorCode(null);
        intents.save(intent);
    }

    @Transactional(readOnly = true)
    public List<String> dueDiscardIds() {
        return intents.findDue(List.of(FileWriteIntentStatus.DISCARD_PENDING,
                        FileWriteIntentStatus.DISCARDING), LocalDateTime.now(), PageRequest.of(0, 100))
                .stream().map(FileWriteIntent::getId).toList();
    }

    public boolean discardAttemptsExhausted(FileWriteIntent intent) {
        return intent.getAttempts() >= Math.max(1, maxDiscardAttempts);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileWriteIntent claimDiscard(String id) {
        FileWriteIntent intent = lock(id);
        LocalDateTime now = LocalDateTime.now();
        if (intent.getStatus() != FileWriteIntentStatus.DISCARD_PENDING
                && intent.getStatus() != FileWriteIntentStatus.DISCARDING) return null;
        if (intent.getRetentionUntil() == null || intent.getRetentionUntil().isAfter(now)
                || intent.getNextAttemptAt() != null && intent.getNextAttemptAt().isAfter(now)) return null;
        if (intent.getAttempts() >= Math.max(1, maxDiscardAttempts)) {
            intent.setStatus(FileWriteIntentStatus.MANUAL_REVIEW);
            intent.setLastErrorCode("DISCARD_RETRY_EXHAUSTED");
            intent.setNextAttemptAt(null);
            intents.save(intent);
            return null;
        }
        intent.setStatus(FileWriteIntentStatus.DISCARDING);
        intent.setAttempts(intent.getAttempts() + 1);
        intent.setNextAttemptAt(now.plusSeconds(Math.max(1, discardLeaseSeconds)));
        return intents.save(intent);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void discarded(String id) {
        FileWriteIntent intent = lock(id);
        if (intent.getStatus() != FileWriteIntentStatus.DISCARDING) return;
        intent.setStatus(FileWriteIntentStatus.DISCARDED);
        intent.setDiscardedAt(LocalDateTime.now());
        intent.setNextAttemptAt(null);
        intent.setLastErrorCode(null);
        intents.save(intent);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void discardFailed(String id, RuntimeException failure) {
        FileWriteIntent intent = lock(id);
        if (intent.getStatus() != FileWriteIntentStatus.DISCARDING) return;
        boolean exhausted = intent.getAttempts() >= Math.max(1, maxDiscardAttempts);
        intent.setStatus(exhausted ? FileWriteIntentStatus.MANUAL_REVIEW : FileWriteIntentStatus.DISCARD_PENDING);
        intent.setNextAttemptAt(exhausted ? null : LocalDateTime.now().plusSeconds(
                Math.min(3600L, 30L << Math.min(7, intent.getAttempts() - 1))));
        intent.setLastErrorCode(cleanCode(failure.getClass().getSimpleName()));
        intents.save(intent);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void discardBlocked(String id, String errorCode) {
        FileWriteIntent intent = lock(id);
        if (intent.getStatus() != FileWriteIntentStatus.DISCARDING) return;
        intent.setStatus(FileWriteIntentStatus.MANUAL_REVIEW);
        intent.setNextAttemptAt(null);
        intent.setLastErrorCode(cleanCode(errorCode));
        intents.save(intent);
    }

    @Transactional(readOnly = true)
    public FileWriteIntent require(String id) { return intents.findById(id)
            .orElseThrow(com.coffer.auth.service.ResourceNotFoundException::new); }

    @Transactional(readOnly = true)
    public java.util.Optional<FileWriteIntent> existing(String id) { return intents.findById(id); }

    @Transactional(readOnly = true)
    public List<String> dueIds(boolean startup) {
        List<FileWriteIntentStatus> states = List.of(FileWriteIntentStatus.PREPARED,
                FileWriteIntentStatus.OBJECT_WRITTEN, FileWriteIntentStatus.FAILED);
        if (startup) return intents.findReadyAtStartup(
                        List.of(FileWriteIntentStatus.PREPARED, FileWriteIntentStatus.OBJECT_WRITTEN),
                        FileWriteIntentStatus.FAILED, LocalDateTime.now(), PageRequest.of(0, 100))
                .stream().map(FileWriteIntent::getId).toList();
        return intents.findDue(states, LocalDateTime.now(), PageRequest.of(0, 100))
                .stream().map(FileWriteIntent::getId).toList();
    }

    @Transactional(readOnly = true)
    public List<IntentView> recent() { return recent(0); }

    @Transactional(readOnly = true)
    public List<IntentView> recent(int page) {
        return intents.findByStatusInOrderByCreatedAtDescIdDesc(List.of(FileWriteIntentStatus.PREPARED,
                        FileWriteIntentStatus.OBJECT_WRITTEN, FileWriteIntentStatus.FAILED,
                        FileWriteIntentStatus.MANUAL_REVIEW, FileWriteIntentStatus.DISCARD_PENDING,
                        FileWriteIntentStatus.DISCARDING, FileWriteIntentStatus.DISCARDED,
                        FileWriteIntentStatus.RESOLVED), operationPage(page))
                .stream().map(i -> new IntentView(i.getId(), i.getTaskId(), i.getFileName(),
                        i.getKind(), i.getStatus(), i.getLastErrorCode(), i.getAttempts(),
                        i.getNextAttemptAt(), i.getCreatedAt(), i.getRetentionUntil(),
                        i.getDiscardedAt())).toList();
    }

    public record IntentView(String id, String taskId, String fileName, String kind,
                             FileWriteIntentStatus status, String errorCode, int attempts,
                             LocalDateTime nextAttemptAt, LocalDateTime createdAt,
                             LocalDateTime retentionUntil, LocalDateTime discardedAt) { }

    private PageRequest operationPage(int page) {
        if (page < 0 || page > 10_000) throw new IllegalArgumentException("操作台账页码无效");
        return PageRequest.of(page, 100);
    }

    private FileWriteIntent lock(String id) { return intents.lockById(id)
            .orElseThrow(com.coffer.auth.service.ResourceNotFoundException::new); }

    private void lockOwnerForKeyReservation() {
        owners.lockById(com.coffer.auth.service.TenantContext.requireOwnerId())
                .orElseThrow(com.coffer.auth.service.ResourceNotFoundException::new);
    }

    private String cleanCode(String value) {
        return value == null ? "UNKNOWN" : value.replaceAll("[^A-Za-z0-9_]", "").substring(0, Math.min(64,
                value.replaceAll("[^A-Za-z0-9_]", "").length()));
    }
}
