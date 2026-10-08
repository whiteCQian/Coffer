package com.coffer.file.application;

import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileRenameIntent;
import com.coffer.file.domain.FileRenameIntentStatus;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.infrastructure.persistence.FileRenameIntentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** One DB-only mutation whose intent survives a crash before the rename transaction commits. */
@Service @RequiredArgsConstructor @com.coffer.auth.service.OwnerOnly
public class FileRenameIntentService {
    private final FileRenameIntentRepository intents;
    private final FileMetadataRepository files;
    @org.springframework.beans.factory.annotation.Value("${coffer.storage.rename-max-attempts:2}")
    private int maxAttempts;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String prepare(FileMetadata file, String afterName) {
        String id = id(file.getId(), file.getRevision(), file.getFileName(), afterName);
        if (intents.existsById(id)) return id;
        FileRenameIntent intent = new FileRenameIntent();
        intent.setId(id);
        intent.setFileId(file.getId());
        intent.setExpectedRevision(file.getRevision());
        intent.setBeforeName(file.getFileName());
        intent.setAfterName(afterName);
        intent.setStatus(FileRenameIntentStatus.PREPARED);
        intent.setNextAttemptAt(LocalDateTime.now().plusSeconds(30));
        intents.saveAndFlush(intent);
        return id;
    }

    /** Called in the same transaction as the metadata update. */
    @Transactional
    public void applied(String id, FileMetadata file) {
        FileRenameIntent intent = intents.lockById(id)
                .orElseThrow(com.coffer.auth.service.ResourceNotFoundException::new);
        if (!Objects.equals(file.getId(), intent.getFileId())
                || !Objects.equals(file.getFileName(), intent.getAfterName())
                || !Objects.equals(file.getRevision(), intent.getExpectedRevision() + 1))
            throw new IllegalStateException("重命名事实与持久意图不一致");
        intent.setStatus(FileRenameIntentStatus.APPLIED);
        intent.setErrorCode(null);
        intent.setNextAttemptAt(null);
        intents.save(intent);
    }

    /** Committed separately after a failed recovery transaction rolls back. */
    @Transactional
    public void recordFailure(String id, RuntimeException failure) {
        FileRenameIntent intent = intents.lockById(id)
                .orElseThrow(com.coffer.auth.service.ResourceNotFoundException::new);
        if (intent.getStatus() == FileRenameIntentStatus.APPLIED
                || intent.getStatus() == FileRenameIntentStatus.CONFLICTED
                || intent.getStatus() == FileRenameIntentStatus.MANUAL_REVIEW) return;
        intent.setAttempts(intent.getAttempts() + 1);
        intent.setStatus(intent.getAttempts() >= Math.max(1, maxAttempts)
                ? FileRenameIntentStatus.MANUAL_REVIEW : FileRenameIntentStatus.FAILED);
        intent.setErrorCode(failure.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", ""));
        intent.setNextAttemptAt(intent.getStatus() == FileRenameIntentStatus.MANUAL_REVIEW
                ? null : LocalDateTime.now().plusSeconds(Math.min(3600L,
                        30L << Math.min(7, intent.getAttempts() - 1))));
        intents.save(intent);
    }

    @Transactional
    public void retry(String id) {
        FileRenameIntent intent = intents.lockById(id)
                .orElseThrow(com.coffer.auth.service.ResourceNotFoundException::new);
        if (intent.getStatus() != FileRenameIntentStatus.MANUAL_REVIEW
                && intent.getStatus() != FileRenameIntentStatus.CONFLICTED)
            throw new IllegalStateException("该重命名意图不需要人工重试");
        intent.setAttempts(0);
        intent.setStatus(FileRenameIntentStatus.PREPARED);
        intent.setErrorCode(null);
        intent.setNextAttemptAt(LocalDateTime.now());
        intents.save(intent);
    }

    @Transactional
    public void recoverOne(String id) {
        FileRenameIntent intent = intents.lockById(id)
                .orElseThrow(com.coffer.auth.service.ResourceNotFoundException::new);
        if (intent.getStatus() == FileRenameIntentStatus.APPLIED) return;
        FileMetadata file = files.findByIdForUpdate(intent.getFileId()).orElse(null);
        if (file == null) {
            intent.setStatus(FileRenameIntentStatus.MANUAL_REVIEW);
            intent.setErrorCode("FILE_MISSING");
        } else if (Objects.equals(file.getRevision(), intent.getExpectedRevision() + 1)
                && Objects.equals(file.getFileName(), intent.getAfterName())) {
            intent.setStatus(FileRenameIntentStatus.APPLIED);
            intent.setErrorCode(null);
        } else if (Objects.equals(file.getRevision(), intent.getExpectedRevision())
                && Objects.equals(file.getFileName(), intent.getBeforeName())) {
            file.setFileName(intent.getAfterName());
            file.setRevision(intent.getExpectedRevision() + 1);
            file.setVectorIndexedAt(null);
            files.save(file);
            intent.setStatus(FileRenameIntentStatus.APPLIED);
            intent.setErrorCode(null);
        } else {
            intent.setStatus(FileRenameIntentStatus.CONFLICTED);
            intent.setErrorCode("FILE_CHANGED");
        }
        intent.setNextAttemptAt(null);
        intents.save(intent);
    }

    @Transactional(readOnly = true)
    public List<String> dueIds(boolean startup) {
        List<FileRenameIntentStatus> states = List.of(FileRenameIntentStatus.PREPARED,
                FileRenameIntentStatus.FAILED);
        return (startup ? intents.findReadyAtStartup(FileRenameIntentStatus.PREPARED,
                        FileRenameIntentStatus.FAILED, LocalDateTime.now(), PageRequest.of(0, 100))
                : intents.findRecoverable(states, LocalDateTime.now(), PageRequest.of(0, 100)))
                .stream().map(FileRenameIntent::getId).toList();
    }

    @Transactional(readOnly = true)
    public List<View> recent() { return recent(0); }

    @Transactional(readOnly = true)
    public List<View> recent(int page) {
        if (page < 0 || page > 10_000) throw new IllegalArgumentException("操作台账页码无效");
        return intents.findByOrderByCreatedAtDescIdDesc(PageRequest.of(page, 100)).stream()
                .map(i -> new View(i.getId(), i.getFileId(), i.getBeforeName(), i.getAfterName(),
                        i.getStatus(), i.getErrorCode(), i.getAttempts(), i.getNextAttemptAt(), i.getCreatedAt())).toList();
    }
    public record View(String id, Long fileId, String beforeName, String afterName,
                       FileRenameIntentStatus status, String errorCode, int attempts,
                       LocalDateTime nextAttemptAt, LocalDateTime createdAt) { }

    private static String id(Long fileId, Long revision, String before, String after) {
        try {
            String input = com.coffer.auth.service.TenantContext.requireOwnerId() + "\0" + fileId
                    + "\0" + revision + "\0" + before + "\0" + after;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException("重命名幂等键生成失败", impossible); }
    }
}
