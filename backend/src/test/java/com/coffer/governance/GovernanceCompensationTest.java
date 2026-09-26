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
    @Autowired GovernanceRecoveryCoordinator recoveryCoordinator;
    @Autowired FileMetadataRepository fileRepository;
    @Autowired ArchiveOperationBatchRepository batchRepository;
    @Autowired ArchiveOperationItemRepository itemRepository;
    @Autowired GovernanceCompensationTaskRepository compensationRepository;
    @MockitoBean MinioStorageService minio;
    @MockitoSpyBean ArchiveOperationPersistenceService archivePersistence;

    @BeforeEach void clean() {
        compensationRepository.deleteAll(); itemRepository.deleteAll(); batchRepository.deleteAll();
        fileRepository.deleteAll(); reset(minio);
    }

    @Test void cleanupFailureCreatesDurableTaskAndRetryCompletesOperation() {
        ArchiveOperationItem item = seedPending("comp-cleanup");
        stubArchiveObjects();
        doThrow(new RuntimeException("delete unavailable")).when(minio).deleteFile(null, ownerPath("files/original.txt"));

        archiveService.executeBatch(item.getBatchId());

        ArchiveOperationItem pending = itemRepository.findById(item.getId()).orElseThrow();
        GovernanceCompensationTask task = compensationRepository.findAll().get(0);
        assertThat(pending.getExecutionStatus()).isEqualTo(ArchiveOperationItemExecutionStatus.CLEANUP_PENDING);
        assertThat(task.getAction()).isEqualTo(GovernanceCompensationAction.DELETE_ARCHIVE_SOURCE);

        reset(minio);
        doNothing().when(minio).deleteFile(null, ownerPath("files/original.txt"));
        processor.process(task.getId());

        assertThat(itemRepository.findById(item.getId()).orElseThrow().getExecutionStatus())
                .isEqualTo(ArchiveOperationItemExecutionStatus.SUCCEEDED);
        assertThat(compensationRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(GovernanceCompensationStatus.SUCCEEDED);
    }

    @Test void repeatedExecutionDoesNotCopyOrCommitTwice() {
        ArchiveOperationItem item = seedPending("comp-idempotent");
        stubArchiveObjects();
        doNothing().when(minio).deleteFile(null, ownerPath("files/original.txt"));
        archiveService.executeBatch(item.getBatchId());
        archiveService.executeBatch(item.getBatchId());
        verify(minio, times(1)).copyObject(ownerPath("files/original.txt"), ownerPath("contracts/archived.txt"));
        assertThat(fileRepository.findById(item.getFileId()).orElseThrow().getRevision()).isEqualTo(1L);
    }

    @Test void databaseFailureAfterCopyCreatesResumeTaskAndKeepsFormalState() {
        ArchiveOperationItem item = seedPending("comp-db");
        stubArchiveObjects();
        doThrow(new RuntimeException("database unavailable")).when(archivePersistence)
                .applyFormalState(eq(item.getId()), anyString(), anyLong(), any(), anyList());

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
        recoveryCoordinator.recover();
        recoveryCoordinator.recover();
        assertThat(compensationRepository.findAll()).hasSize(1);
        assertThat(compensationRepository.findAll().get(0).getAction())
                .isEqualTo(GovernanceCompensationAction.RESUME_ARCHIVE);
    }

    private ArchiveOperationItem seedPending(String batchId) {
        FileMetadata file = fileRepository.saveAndFlush(FileMetadata.builder().fileName("original.txt")
                .fileSize(12L).fileType("txt").storagePath(ownerPath("files/original.txt")).status(FileStatus.COMPLETED)
                .category(CategoryType.REPORT).revision(0L).contentEtag("source-etag").build());
        batchRepository.saveAndFlush(ArchiveOperationBatch.builder().batchId(batchId)
                .source(ArchiveOperationSource.PREVIEW_CONFIRMATION).runMode(GovernanceRunMode.LOCAL)
                .requestId("request-" + batchId).status(ArchiveOperationBatchStatus.PENDING).totalCount(1).build());
        return itemRepository.saveAndFlush(ArchiveOperationItem.builder().batchId(batchId).fileId(file.getId())
                .itemKey(batchId + ":" + file.getId()).sourceFileName("original.txt")
                .targetFileName("archived.txt").sourceCategory("REPORT").targetCategory("CONTRACT")
                .sourcePath(ownerPath("files/original.txt")).targetPath(ownerPath("contracts/archived.txt"))
                .sourceEtag("source-etag").sourceSize(12L).build());
    }

    private void stubArchiveObjects() {
        when(minio.objectExists(null, ownerPath("contracts/archived.txt"))).thenReturn(false);
        when(minio.statFile(isNull(), eq(ownerPath("files/original.txt"))))
                .thenReturn(new MinioStorageService.ObjectSnapshot("source-etag", 12));
        when(minio.statFile(isNull(), eq(ownerPath("contracts/archived.txt"))))
                .thenReturn(new MinioStorageService.ObjectSnapshot("target-etag", 12));
        doNothing().when(minio).copyObject(anyString(), anyString());
    }
}
