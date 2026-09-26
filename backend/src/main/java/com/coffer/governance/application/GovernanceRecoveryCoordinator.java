package com.coffer.governance.application;

import com.coffer.governance.domain.*;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationItemRepository;
import com.coffer.auth.service.TenantJobRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** Recreates durable compensation work for operations interrupted by a process crash. */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceRecoveryCoordinator {
    private final ArchiveOperationItemRepository itemRepository;
    private final GovernanceCompensationRegistry registry;
    private final ArchiveOperationPersistenceService archivePersistence;
    private final ArchiveRollbackPersistenceService rollbackPersistence;
    private final TenantJobRunner tenantJobRunner;

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        tenantJobRunner.runForEnabledOwners(ownerId -> recoverForOwner());
    }

    private void recoverForOwner() {
        registry.recoverInterruptedTasks();
        recoverArchiveItems();
        recoverRollbackItems();
    }

    private void recoverArchiveItems() {
        List<ArchiveOperationItemExecutionStatus> statuses = List.of(
                ArchiveOperationItemExecutionStatus.PENDING, ArchiveOperationItemExecutionStatus.VALIDATING,
                ArchiveOperationItemExecutionStatus.COPYING, ArchiveOperationItemExecutionStatus.DB_COMMITTING,
                ArchiveOperationItemExecutionStatus.CLEANUP_PENDING);
        for (ArchiveOperationItem item : itemRepository.findByExecutionStatusIn(statuses)) {
            if (item.getExecutionStatus() == ArchiveOperationItemExecutionStatus.CLEANUP_PENDING
                    && item.getExecutionStep() == ArchiveOperationItemExecutionStep.OLD_OBJECT_CLEANUP_PENDING) {
                if (Objects.equals(item.getSourcePath(), item.getTargetPath())) archivePersistence.markSucceeded(item.getId());
                else registry.register(item.getBatchId(), item.getId(), GovernanceCompensationAction.DELETE_ARCHIVE_SOURCE,
                        item.getSourcePath(), "服务重启后恢复旧对象清理");
            } else {
                registry.register(item.getBatchId(), item.getId(), GovernanceCompensationAction.RESUME_ARCHIVE,
                        item.getTargetPath(), "服务重启后恢复归档执行");
            }
        }
    }

    private void recoverRollbackItems() {
        List<ArchiveOperationItemRollbackStatus> statuses = List.of(
                ArchiveOperationItemRollbackStatus.PENDING, ArchiveOperationItemRollbackStatus.VALIDATING,
                ArchiveOperationItemRollbackStatus.COPYING, ArchiveOperationItemRollbackStatus.DB_COMMITTING,
                ArchiveOperationItemRollbackStatus.CLEANUP_PENDING);
        for (ArchiveOperationItem item : itemRepository.findByRollbackStatusIn(statuses)) {
            if (item.getRollbackStatus() == ArchiveOperationItemRollbackStatus.CLEANUP_PENDING) {
                if (Objects.equals(item.getSourcePath(), item.getTargetPath())) rollbackPersistence.markSucceeded(item.getId());
                else registry.register(item.getBatchId(), item.getId(), GovernanceCompensationAction.DELETE_ROLLBACK_TARGET,
                        item.getTargetPath(), "服务重启后恢复归档对象清理");
            } else {
                registry.register(item.getBatchId(), item.getId(), GovernanceCompensationAction.RESUME_ROLLBACK,
                        item.getSourcePath(), "服务重启后恢复撤销执行");
            }
        }
        log.info("治理操作中断恢复扫描完成");
    }
}
