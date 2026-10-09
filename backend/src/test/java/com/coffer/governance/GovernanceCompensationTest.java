package com.coffer.governance;

import com.coffer.file.domain.*;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.governance.application.*;
import com.coffer.governance.domain.*;
import com.coffer.governance.infrastructure.persistence.*;
import com.coffer.service.MinioStorageService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:governance_compensation_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.flyway.locations=classpath:db/migration/h2",
        "minio.access-key=test-access-key", "minio.secret-key=test-secret-key",
        "coffer.governance.archive.compensation-interval-ms=3600000"
})
class GovernanceCompensationTest extends com.coffer.auth.OwnerTestSupport {
    @Autowired ArchiveOperationService archiveService;
    @Autowired GovernanceCompensationProcessor processor;
    @Autowired GovernanceCompensationRegistry registry;
    @Autowired GovernanceRecoveryCoordinator recoveryCoordinator;
    @Autowired FileMetadataRepository fileRepository;
    @Autowired ArchiveOperationBatchRepository batchRepository;
    @Autowired ArchiveOperationItemRepository itemRepository;
    @Autowired GovernanceCompensationTaskRepository compensationRepository;
    @MockitoBean MinioStorageService minio;
    @MockitoSpyBean ArchiveOperationPersistenceService archivePersistence;
    private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @BeforeEach void clean() {
        compensationRepository.deleteAll(); itemRepository.deleteAll(); batchRepository.deleteAll();
        fileRepository.deleteAll(); reset(minio);
    }

    @Test void cleanupFailureCreatesDurableTaskAndRetryCompletesOperation() {
        ArchiveOperationItem item = seedPending("comp-cleanup");
        stubArchiveObjects();
        doThrow(new RuntimeException("delete unavailable")).when(minio).delete(ownerPath("files/original.txt"), SHA);

        archiveService.executeBatch(item.getBatchId());

        ArchiveOperationItem pending = itemRepository.findById(item.getId()).orElseThrow();
        GovernanceCompensationTask task = compensationRepository.findAll().get(0);
        assertThat(pending.getExecutionStatus()).isEqualTo(ArchiveOperationItemExecutionStatus.CLEANUP_PENDING);
        assertThat(task.getAction()).isEqualTo(GovernanceCompensationAction.DELETE_ARCHIVE_SOURCE);

        reset(minio);
        when(minio.stat(ownerPath("contracts/archived.txt")))
                .thenReturn(new com.coffer.file.storage.FileStoragePort.StoredObject(ownerPath("contracts/archived.txt"), 12, SHA, "target-etag"));
        processor.process(task.getId());

        assertThat(itemRepository.findById(item.getId()).orElseThrow().getExecutionStatus())
                .isEqualTo(ArchiveOperationItemExecutionStatus.SUCCEEDED);
        assertThat(compensationRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(GovernanceCompensationStatus.SUCCEEDED);
    }

    @Test void repeatedExecutionDoesNotCopyOrCommitTwice() {
        ArchiveOperationItem item = seedPending("comp-idempotent");
        stubArchiveObjects();
        archiveService.executeBatch(item.getBatchId());
        archiveService.executeBatch(item.getBatchId());
        verify(minio, times(1)).copy(ownerPath("files/original.txt"), ownerPath("contracts/archived.txt"), SHA);
        assertThat(fileRepository.findById(item.getFileId()).orElseThrow().getRevision()).isEqualTo(1L);
    }

    @Test void databaseFailureAfterCopyCreatesResumeTaskAndKeepsFormalState() {
        ArchiveOperationItem item = seedPending("comp-db");
        stubArchiveObjects();
        doThrow(new RuntimeException("database unavailable")).when(archivePersistence)
                .applyFormalState(eq(item.getId()), anyString(), anyLong(), anyString(), any(), anyList());

        archiveService.executeBatch(item.getBatchId());

        FileMetadata file = fileRepository.findById(item.getFileId()).orElseThrow();
        GovernanceCompensationTask task = compensationRepository.findAll().get(0);
        assertThat(file.getStoragePath()).isEqualTo(ownerPath("files/original.txt"));
        assertThat(file.getRevision()).isZero();
        assertThat(task.getAction()).isEqualTo(GovernanceCompensationAction.RESUME_ARCHIVE);
        assertThat(itemRepository.findById(item.getId()).orElseThrow().getExecutionStatus())
                .isEqualTo(ArchiveOperationItemExecutionStatus.FAILED);
    }

    @Test void startupRecoveryIsIdempotentForInterruptedCopy() {
        ArchiveOperationItem item = seedPending("comp-recovery");
        item.setExecutionStatus(ArchiveOperationItemExecutionStatus.COPYING);
        item.setExecutionStep(ArchiveOperationItemExecutionStep.SOURCE_VERIFIED);
        itemRepository.saveAndFlush(item);
        stubArchiveObjects();
        recoveryCoordinator.recover();
        recoveryCoordinator.recover();
        assertThat(compensationRepository.findAll()).hasSize(1);
        assertThat(compensationRepository.findAll().get(0).getAction())
                .isEqualTo(GovernanceCompensationAction.RESUME_ARCHIVE);
        processor.process(compensationRepository.findAll().get(0).getId());
        assertThat(itemRepository.findById(item.getId()).orElseThrow().getExecutionStatus())
                .isEqualTo(ArchiveOperationItemExecutionStatus.SUCCEEDED);
        assertThat(batchRepository.findByBatchId(item.getBatchId()).orElseThrow().getStatus())
                .isEqualTo(ArchiveOperationBatchStatus.SUCCEEDED);
    }

    @Test void startupNormalizesPreviouslyExhaustedArchiveItem() {
        ArchiveOperationItem item = seedPending("comp-legacy-limit");
        item.setExecutionStatus(ArchiveOperationItemExecutionStatus.FAILED);
        item.setAttempts(2);
        item.setNextAttemptAt(java.time.LocalDateTime.now().plusHours(1));
        itemRepository.saveAndFlush(item);

        recoveryCoordinator.recover();

        ArchiveOperationItem current = itemRepository.findById(item.getId()).orElseThrow();
        assertThat(current.getExecutionStatus()).isEqualTo(ArchiveOperationItemExecutionStatus.MANUAL_REVIEW);
        assertThat(current.getNextAttemptAt()).isNull();
        assertThat(compensationRepository.findAll()).isEmpty();
    }

    @Test void failedCompensationStopsForManualReviewAndRestartPreservesBackoff() {
        ArchiveOperationItem item = seedPending("comp-bounded-retry");
        GovernanceCompensationTask task = registry.register(item.getBatchId(), item.getId(),
                GovernanceCompensationAction.RESUME_ARCHIVE, item.getTargetPath(), "待恢复");
        for (int attempt = 1; attempt <= 2; attempt++) {
            assertThat(registry.claim(task.getId())).isNotNull();
            registry.failed(task.getId(), "存储暂不可用");
            GovernanceCompensationTask persisted = compensationRepository.findById(task.getId()).orElseThrow();
            if (attempt < 2) {
                assertThat(persisted.getStatus()).isEqualTo(GovernanceCompensationStatus.FAILED);
                var due = persisted.getNextAttemptAt();
                assertThat(due).isAfter(java.time.LocalDateTime.now());
                registry.register(item.getBatchId(), item.getId(),
                        GovernanceCompensationAction.RESUME_ARCHIVE, item.getTargetPath(), "重启扫描");
                assertThat(compensationRepository.findById(task.getId()).orElseThrow().getNextAttemptAt())
                        .isEqualTo(due);
                assertThat(registry.claim(task.getId())).isNull();
                persisted.setNextAttemptAt(java.time.LocalDateTime.now().minusSeconds(1));
                compensationRepository.saveAndFlush(persisted);
            }
        }
        assertThat(compensationRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(GovernanceCompensationStatus.MANUAL_REVIEW);
        recoveryCoordinator.recover();
        assertThat(compensationRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(GovernanceCompensationStatus.MANUAL_REVIEW);
        assertThat(registry.claim(task.getId())).isNull();

        registry.retryBatch(item.getBatchId());
        GovernanceCompensationTask retried = compensationRepository.findById(task.getId()).orElseThrow();
        assertThat(retried.getStatus()).isEqualTo(GovernanceCompensationStatus.PENDING);
        assertThat(retried.getAttempts()).isZero();
        assertThat(registry.claim(task.getId())).isNotNull();
    }

    @Test void crashOnLastAllowedAttemptRequiresReviewOnRestart() {
        ArchiveOperationItem item = seedPending("comp-crash-limit");
        GovernanceCompensationTask task = registry.register(item.getBatchId(), item.getId(),
                GovernanceCompensationAction.RESUME_ARCHIVE, item.getTargetPath(), "待恢复");
        task.setAttempts(1);
        compensationRepository.saveAndFlush(task);
        assertThat(registry.claim(task.getId()).getAttempts()).isEqualTo(2);
        registry.recoverInterruptedTasks();
        assertThat(compensationRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(GovernanceCompensationStatus.MANUAL_REVIEW);
    }

    private ArchiveOperationItem seedPending(String batchId) {
        FileMetadata file = fileRepository.saveAndFlush(FileMetadata.builder().fileName("original.txt")
                .fileSize(12L).fileType("txt").storagePath(ownerPath("files/original.txt")).status(FileStatus.COMPLETED)
                .category(CategoryType.REPORT).revision(0L).contentEtag("source-etag").contentSha256(SHA).build());
        batchRepository.saveAndFlush(ArchiveOperationBatch.builder().batchId(batchId)
                .source(ArchiveOperationSource.PREVIEW_CONFIRMATION).runMode(GovernanceRunMode.LOCAL)
                .requestId("request-" + batchId).status(ArchiveOperationBatchStatus.PENDING).totalCount(1).build());
        return itemRepository.saveAndFlush(ArchiveOperationItem.builder().batchId(batchId).fileId(file.getId())
                .itemKey(batchId + ":" + file.getId()).sourceFileName("original.txt")
                .targetFileName("archived.txt").sourceCategory("REPORT").targetCategory("CONTRACT")
                .sourcePath(ownerPath("files/original.txt")).targetPath(ownerPath("contracts/archived.txt"))
                .sourceEtag("source-etag").sourceSize(12L).sourceSha256(SHA).build());
    }

    private void stubArchiveObjects() {
        when(minio.exists(ownerPath("contracts/archived.txt"))).thenReturn(false);
        when(minio.stat(ownerPath("files/original.txt")))
                .thenReturn(new com.coffer.file.storage.FileStoragePort.StoredObject(ownerPath("files/original.txt"), 12, SHA, "source-etag"));
        when(minio.stat(ownerPath("contracts/archived.txt")))
                .thenReturn(new com.coffer.file.storage.FileStoragePort.StoredObject(ownerPath("contracts/archived.txt"), 12, SHA, "target-etag"));
    }
}
