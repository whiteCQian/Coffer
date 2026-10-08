package com.coffer.governance;

import com.coffer.file.domain.CategoryType;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.governance.application.ArchiveRollbackPersistenceService;
import com.coffer.governance.application.ArchiveRollbackService;
import com.coffer.governance.application.GovernanceCompensationProcessor;
import com.coffer.governance.application.GovernanceRecoveryCoordinator;
import com.coffer.governance.domain.*;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationBatchRepository;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationItemRepository;
import com.coffer.governance.infrastructure.persistence.GovernanceCompensationTaskRepository;
import com.coffer.service.MinioStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:archive_rollback_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.flyway.locations=classpath:db/migration/h2",
        "minio.access-key=test-access-key", "minio.secret-key=test-secret-key"
})
class ArchiveRollbackExecutionTest extends com.coffer.auth.OwnerTestSupport {
    @Autowired ArchiveRollbackService rollbackService;
    @Autowired ArchiveRollbackPersistenceService rollbackPersistenceService;
    @Autowired GovernanceCompensationProcessor compensationProcessor;
    @Autowired GovernanceRecoveryCoordinator recoveryCoordinator;
    @Autowired GovernanceCompensationTaskRepository compensationRepository;
    @Autowired FileMetadataRepository fileRepository;
    @Autowired ArchiveOperationBatchRepository batchRepository;
    @Autowired ArchiveOperationItemRepository itemRepository;
    @MockitoBean MinioStorageService minio;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @BeforeEach void clean() {
        compensationRepository.deleteAll(); itemRepository.deleteAll(); batchRepository.deleteAll();
        fileRepository.deleteAll(); reset(minio);
    }

    @Test void restoresOriginalPathCategoryAndName() {
        ArchiveOperationItem item = seed("rollback-ok", 1L);
        stubTarget(true, "target-etag");
        when(minio.exists(eq(ownerPath("files/original.txt")))).thenReturn(false);
        when(minio.stat(eq(ownerPath("files/original.txt"))))
                .thenReturn(object(ownerPath("files/original.txt"), "restored-etag"));

        rollbackPersistenceService.prepareItem(item.getBatchId(), item.getId());
        rollbackService.executeItem(item.getId());

        FileMetadata file = fileRepository.findById(item.getFileId()).orElseThrow();
        ArchiveOperationItem saved = itemRepository.findById(item.getId()).orElseThrow();
        assertThat(file.getFileName()).isEqualTo("original.txt");
        assertThat(file.getStoragePath()).isEqualTo(ownerPath("files/original.txt"));
        assertThat(file.getCategory()).isEqualTo(CategoryType.REPORT);
        assertThat(file.isArchived()).isFalse();
        assertThat(file.getRevision()).isEqualTo(2L);
        assertThat(saved.getRollbackStatus()).isEqualTo(ArchiveOperationItemRollbackStatus.SUCCEEDED);
        verify(minio).copy(ownerPath("contracts/archived.txt"), ownerPath("files/original.txt"), SHA);
    }

    @Test void occupiedOriginalPathBecomesConflictWithoutOverwrite() {
        ArchiveOperationItem item = seed("rollback-collision", 1L);
        stubTarget(true, "target-etag");
        when(minio.exists(eq(ownerPath("files/original.txt")))).thenReturn(true);
        rollbackPersistenceService.prepareItem(item.getBatchId(), item.getId());
        rollbackService.executeItem(item.getId());
        assertThat(itemRepository.findById(item.getId()).orElseThrow().getRollbackStatus())
                .isEqualTo(ArchiveOperationItemRollbackStatus.CONFLICTED);
        assertThat(fileRepository.findById(item.getFileId()).orElseThrow().getStoragePath())
                .isEqualTo(ownerPath("contracts/archived.txt"));
        verify(minio, never()).copy(anyString(), anyString(), anyString());
    }

    @Test void changedFileBecomesConflictAndDeletedObjectIsNotReversible() {
        ArchiveOperationItem changed = seed("rollback-changed", 2L);
        rollbackPersistenceService.prepareItem(changed.getBatchId(), changed.getId());
        rollbackService.executeItem(changed.getId());
        assertThat(itemRepository.findById(changed.getId()).orElseThrow().getRollbackStatus())
                .isEqualTo(ArchiveOperationItemRollbackStatus.CONFLICTED);

        ArchiveOperationItem missing = seed("rollback-missing", 1L);
        when(minio.exists(eq(ownerPath("contracts/archived.txt")))).thenReturn(false);
        rollbackPersistenceService.prepareItem(missing.getBatchId(), missing.getId());
        rollbackService.executeItem(missing.getId());
        assertThat(itemRepository.findById(missing.getId()).orElseThrow().getRollbackStatus())
                .isEqualTo(ArchiveOperationItemRollbackStatus.NOT_REVERSIBLE);
    }

    @Test void repeatedSucceededRollbackDoesNotRunAgain() {
        ArchiveOperationItem item = seed("rollback-repeat", 1L);
        item.setRollbackStatus(ArchiveOperationItemRollbackStatus.SUCCEEDED);
        itemRepository.saveAndFlush(item);
        assertThat(rollbackPersistenceService.prepareItem(item.getBatchId(), item.getId())).isFalse();
        rollbackService.executeItem(item.getId());
        verifyNoInteractions(minio);
    }

    @Test void batchRollbackAggregatesSuccessfulResult() {
        ArchiveOperationItem item = seed("rollback-batch", 1L);
        stubTarget(true, "target-etag");
        when(minio.exists(eq(ownerPath("files/original.txt")))).thenReturn(false);
        when(minio.stat(eq(ownerPath("files/original.txt"))))
                .thenReturn(object(ownerPath("files/original.txt"), "restored-etag"));

        assertThat(rollbackPersistenceService.prepareBatch(item.getBatchId())).isTrue();
        rollbackService.executeBatch(item.getBatchId());

        assertThat(batchRepository.findByBatchId(item.getBatchId()).orElseThrow().getRollbackStatus())
                .isEqualTo(ArchiveOperationRollbackStatus.SUCCEEDED);
        assertThat(itemRepository.findById(item.getId()).orElseThrow().getRollbackStatus())
                .isEqualTo(ArchiveOperationItemRollbackStatus.SUCCEEDED);
    }

    @Test void interruptedRollbackRecoveryRecomputesBatchStatus() {
        ArchiveOperationItem item = seed("rollback-interrupted", 1L);
        assertThat(rollbackPersistenceService.prepareBatch(item.getBatchId())).isTrue();
        item = itemRepository.findById(item.getId()).orElseThrow();
        item.setRollbackStatus(ArchiveOperationItemRollbackStatus.COPYING);
        itemRepository.saveAndFlush(item);
        stubTarget(true, "target-etag");
        when(minio.exists(eq(ownerPath("files/original.txt")))).thenReturn(false);
        when(minio.stat(eq(ownerPath("files/original.txt"))))
                .thenReturn(object(ownerPath("files/original.txt"), "restored-etag"));

        recoveryCoordinator.recover();
        assertThat(compensationRepository.findAll()).singleElement()
                .extracting(GovernanceCompensationTask::getAction)
                .isEqualTo(GovernanceCompensationAction.RESUME_ROLLBACK);
        compensationProcessor.process(compensationRepository.findAll().get(0).getId());

        assertThat(itemRepository.findById(item.getId()).orElseThrow().getRollbackStatus())
                .isEqualTo(ArchiveOperationItemRollbackStatus.SUCCEEDED);
        assertThat(batchRepository.findByBatchId(item.getBatchId()).orElseThrow().getRollbackStatus())
                .isEqualTo(ArchiveOperationRollbackStatus.SUCCEEDED);
    }

    @Test void verifiedRollbackCopySurvivesRecoveryStatusReset() {
        ArchiveOperationItem item = seed("rollback-verified-copy", 1L);
        stubTarget(true, "target-etag");
        when(minio.exists(eq(ownerPath("files/original.txt")))).thenReturn(true);
        when(minio.stat(eq(ownerPath("files/original.txt"))))
                .thenReturn(object(ownerPath("files/original.txt"), "restored-etag"));
        assertThat(rollbackPersistenceService.prepareBatch(item.getBatchId())).isTrue();
        rollbackPersistenceService.claim(item.getId());
        rollbackPersistenceService.markCopying(item.getId());
        rollbackPersistenceService.markDbCommitting(item.getId());

        rollbackPersistenceService.prepareRecovery(item.getId());
        assertThat(itemRepository.findById(item.getId()).orElseThrow().isRollbackCopyVerified()).isTrue();
        rollbackService.executeItem(item.getId());

        assertThat(itemRepository.findById(item.getId()).orElseThrow().getRollbackStatus())
                .isEqualTo(ArchiveOperationItemRollbackStatus.SUCCEEDED);
        assertThat(fileRepository.findById(item.getFileId()).orElseThrow().getStoragePath())
                .isEqualTo(ownerPath("files/original.txt"));
        verify(minio, never()).copy(anyString(), anyString(), anyString());
    }

    private ArchiveOperationItem seed(String batchId, long currentRevision) {
        FileMetadata file = fileRepository.saveAndFlush(FileMetadata.builder().fileName("archived.txt")
                .fileSize(12L).fileType("txt").storagePath(ownerPath("contracts/archived.txt"))
                .status(FileStatus.COMPLETED).category(CategoryType.CONTRACT).archived(true)
                .revision(currentRevision).contentEtag("target-etag").contentSha256(SHA).build());
        var source = new ArchiveFormalSnapshot("original.txt", ownerPath("files/original.txt"),
                "REPORT", null, java.util.List.of(), false, 0L, SHA, 12L, "source-etag");
        var target = new ArchiveFormalSnapshot("archived.txt", ownerPath("contracts/archived.txt"),
                "CONTRACT", null, java.util.List.of(), true, 1L, SHA, 12L, "target-etag");
        batchRepository.saveAndFlush(ArchiveOperationBatch.builder().batchId(batchId)
                .source(ArchiveOperationSource.PREVIEW_CONFIRMATION).runMode(GovernanceRunMode.LOCAL)
                .requestId("request-" + batchId).status(ArchiveOperationBatchStatus.SUCCEEDED).totalCount(1)
                .successCount(1).build());
        return itemRepository.saveAndFlush(ArchiveOperationItem.builder().batchId(batchId).fileId(file.getId())
                .itemKey(batchId + ":" + file.getId()).sourceFileName("original.txt")
                .targetFileName("archived.txt").sourceCategory("REPORT").targetCategory("CONTRACT")
                .sourcePath(ownerPath("files/original.txt")).targetPath(ownerPath("contracts/archived.txt"))
                .sourceEtag("source-etag").sourceSize(12L).targetEtag("target-etag").targetSize(12L)
                .sourceSha256(SHA).targetSha256(SHA).snapshotVersion(1)
                .sourceSnapshotJson(write(source)).targetSnapshotJson(write(target))
                .preExecuteRevision(0L).postExecuteRevision(1L)
                .executionStatus(ArchiveOperationItemExecutionStatus.SUCCEEDED).build());
    }

    private void stubTarget(boolean exists, String etag) {
        when(minio.exists(eq(ownerPath("contracts/archived.txt")))).thenReturn(exists);
        when(minio.stat(eq(ownerPath("contracts/archived.txt"))))
                .thenReturn(object(ownerPath("contracts/archived.txt"), etag));
    }

    private com.coffer.file.storage.FileStoragePort.StoredObject object(String path, String etag) {
        return new com.coffer.file.storage.FileStoragePort.StoredObject(path, 12, SHA, etag);
    }
    private String write(ArchiveFormalSnapshot snapshot) {
        try { return json.writeValueAsString(snapshot); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
