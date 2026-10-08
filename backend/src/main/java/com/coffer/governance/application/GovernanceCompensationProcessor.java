package com.coffer.governance.application;

import com.coffer.governance.domain.*;
import com.coffer.governance.infrastructure.persistence.GovernanceCompensationTaskRepository;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationItemRepository;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.file.storage.StorageObjectNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class GovernanceCompensationProcessor {
    private final GovernanceCompensationTaskRepository repository;
    private final GovernanceCompensationRegistry registry;
    private final ArchiveOperationPersistenceService archivePersistence;
    private final ArchiveRollbackPersistenceService rollbackPersistence;
    private final ArchiveOperationService archiveService;
    private final ArchiveRollbackService rollbackService;
    private final FileStoragePort storage;
    private final ArchiveOperationItemRepository itemRepository;

    @com.coffer.auth.service.OwnerScheduled
    @Scheduled(fixedDelayString = "${coffer.governance.archive.compensation-interval-ms:30000}")
    public void processDue() {
        repository.findDue(List.of(GovernanceCompensationStatus.PENDING, GovernanceCompensationStatus.FAILED),
                LocalDateTime.now(), PageRequest.of(0, 20)).forEach(task -> process(task.getId()));
    }

    public void process(Long id) {
        GovernanceCompensationTask task = registry.claim(id);
        if (task == null) return;
        try {
            switch (task.getAction()) {
                case RESUME_ARCHIVE -> {
                    archivePersistence.prepareRecovery(task.getItemId());
                    archiveService.executeItem(task.getItemId());
                    ArchiveOperationItem item = requireItem(task.getItemId());
                    if (item.getExecutionStatus() == ArchiveOperationItemExecutionStatus.FAILED
                            || item.getExecutionStatus() == ArchiveOperationItemExecutionStatus.MANUAL_REVIEW) {
                        throw new IllegalStateException("归档恢复执行仍然失败: " + item.getFailureMessage());
                    }
                    archivePersistence.recomputeBatch(task.getBatchId());
                }
                case DELETE_ARCHIVE_SOURCE -> {
                    ArchiveOperationItem item = requireItem(task.getItemId());
                    requireExpectedContent(item.getTargetPath(), item.getTargetSha256());
                    deleteIfPresent(task.getObjectPath(), item.getSourceSha256());
                    archivePersistence.markSucceeded(task.getItemId());
                    archivePersistence.recomputeBatch(task.getBatchId());
                }
                case RESUME_ROLLBACK -> {
                    rollbackPersistence.prepareRecovery(task.getItemId());
                    rollbackService.executeItem(task.getItemId());
                    ArchiveOperationItem item = requireItem(task.getItemId());
                    if (item.getRollbackStatus() == ArchiveOperationItemRollbackStatus.FAILED) {
                        throw new IllegalStateException("撤销恢复执行仍然失败: " + item.getFailureMessage());
                    }
                    rollbackPersistence.recomputeBatch(task.getBatchId());
                }
                case DELETE_ROLLBACK_TARGET -> {
                    ArchiveOperationItem item = requireItem(task.getItemId());
                    requireExpectedContent(item.getSourcePath(), item.getSourceSha256());
                    deleteIfPresent(task.getObjectPath(), item.getTargetSha256());
                    rollbackPersistence.markSucceeded(task.getItemId());
                    rollbackPersistence.recomputeBatch(task.getBatchId());
                }
            }
            registry.succeeded(id);
        } catch (Exception e) {
            log.warn("治理补偿失败，操作类型={}，异常类型={}", task.getAction(), e.getClass().getSimpleName());
            registry.failed(id, "治理补偿失败，请稍后重试");
        }
    }

    private ArchiveOperationItem requireItem(Long id) {
        return itemRepository.findById(id).orElseThrow(() -> new com.coffer.auth.service.ResourceNotFoundException());
    }

    private void requireExpectedContent(String path, String expectedSha256) {
        if (expectedSha256 == null || !expectedSha256.equalsIgnoreCase(storage.stat(path).sha256())) {
            throw new IllegalStateException("治理对象内容与台账不一致，拒绝完成补偿");
        }
    }

    private void deleteIfPresent(String path, String expectedSha256) {
        if (expectedSha256 == null) throw new IllegalStateException("治理台账缺少对象内容摘要，需人工处理");
        try { storage.delete(path, expectedSha256); }
        catch (StorageObjectNotFoundException alreadyRemoved) { /* prior cleanup completed */ }
    }
}
