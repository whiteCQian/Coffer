package com.coffer.governance;

import com.coffer.file.domain.CategoryType;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.governance.application.ArchiveRollbackPersistenceService;
import com.coffer.governance.application.ArchiveRollbackService;
import com.coffer.governance.domain.*;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationBatchRepository;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationItemRepository;
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
class ArchiveRollbackExecutionTest {
    @Autowired ArchiveRollbackService rollbackService;
    @Autowired ArchiveRollbackPersistenceService rollbackPersistenceService;
    @Autowired FileMetadataRepository fileRepository;
    @Autowired ArchiveOperationBatchRepository batchRepository;
    @Autowired ArchiveOperationItemRepository itemRepository;
    @MockitoBean MinioStorageService minio;

    @BeforeEach void clean() {
        itemRepository.deleteAll(); batchRepository.deleteAll(); fileRepository.deleteAll(); reset(minio);
    }

    @Test void restoresOriginalPathCategoryAndName() {
        ArchiveOperationItem item = seed("rollback-ok", 1L);
        stubTarget(true, "target-etag");
        when(minio.objectExists(isNull(), eq("files/original.txt"))).thenReturn(false);
        when(minio.statFile(isNull(), eq("files/original.txt")))
                .thenReturn(new MinioStorageService.ObjectSnapshot("restored-etag", 12));

        rollbackPersistenceService.prepareItem(item.getBatchId(), item.getId());
        rollbackService.executeItem(item.getId());

        FileMetadata file = fileRepository.findById(item.getFileId()).orElseThrow();
        ArchiveOperationItem saved = itemRepository.findById(item.getId()).orElseThrow();
        assertThat(file.getFileName()).isEqualTo("original.txt");
        assertThat(file.getStoragePath()).isEqualTo("files/original.txt");
        assertThat(file.getCategory()).isEqualTo(CategoryType.REPORT);
        assertThat(file.isArchived()).isFalse();
        assertThat(file.getRevision()).isEqualTo(2L);
        assertThat(saved.getRollbackStatus()).isEqualTo(ArchiveOperationItemRollbackStatus.SUCCEEDED);
        verify(minio).copyObject("contracts/archived.txt", "files/original.txt");
    }

    @Test void occupiedOriginalPathBecomesConflictWithoutOverwrite() {
        ArchiveOperationItem item = seed("rollback-collision", 1L);
        stubTarget(true, "target-etag");
        when(minio.objectExists(isNull(), eq("files/original.txt"))).thenReturn(true);
        when(minio.statFile(isNull(), eq("files/original.txt")))
                .thenReturn(new MinioStorageService.ObjectSnapshot("occupied-etag", 99));
        rollbackPersistenceService.prepareItem(item.getBatchId(), item.getId());
        rollbackService.executeItem(item.getId());
        assertThat(itemRepository.findById(item.getId()).orElseThrow().getRollbackStatus())
                .isEqualTo(ArchiveOperationItemRollbackStatus.CONFLICTED);
        assertThat(fileRepository.findById(item.getFileId()).orElseThrow().getStoragePath())
                .isEqualTo("contracts/archived.txt");
        verify(minio, never()).copyObject(anyString(), anyString());
    }

    @Test void changedFileBecomesConflictAndDeletedObjectIsNotReversible() {
        ArchiveOperationItem changed = seed("rollback-changed", 2L);
        rollbackPersistenceService.prepareItem(changed.getBatchId(), changed.getId());
        rollbackService.executeItem(changed.getId());
        assertThat(itemRepository.findById(changed.getId()).orElseThrow().getRollbackStatus())
                .isEqualTo(ArchiveOperationItemRollbackStatus.CONFLICTED);

        ArchiveOperationItem missing = seed("rollback-missing", 1L);
        when(minio.objectExists(isNull(), eq("contracts/archived.txt"))).thenReturn(false);
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
        when(minio.objectExists(isNull(), eq("files/original.txt"))).thenReturn(false);
        when(minio.statFile(isNull(), eq("files/original.txt")))
                .thenReturn(new MinioStorageService.ObjectSnapshot("restored-etag", 12));

        assertThat(rollbackPersistenceService.prepareBatch(item.getBatchId())).isTrue();
        rollbackService.executeBatch(item.getBatchId());

        assertThat(batchRepository.findByBatchId(item.getBatchId()).orElseThrow().getRollbackStatus())
                .isEqualTo(ArchiveOperationRollbackStatus.SUCCEEDED);
        assertThat(itemRepository.findById(item.getId()).orElseThrow().getRollbackStatus())
                .isEqualTo(ArchiveOperationItemRollbackStatus.SUCCEEDED);
    }

    private ArchiveOperationItem seed(String batchId, long currentRevision) {
        FileMetadata file = fileRepository.saveAndFlush(FileMetadata.builder().fileName("archived.txt")
                .fileSize(12L).fileType("txt").storagePath("contracts/archived.txt")
                .status(FileStatus.COMPLETED).category(CategoryType.CONTRACT).archived(true)
                .revision(currentRevision).contentEtag("target-etag").build());
        batchRepository.saveAndFlush(ArchiveOperationBatch.builder().batchId(batchId)
                .source(ArchiveOperationSource.PREVIEW_CONFIRMATION).runMode(GovernanceRunMode.LOCAL)
                .requestId("request-" + batchId).status(ArchiveOperationBatchStatus.SUCCEEDED).totalCount(1)
                .successCount(1).build());
        return itemRepository.saveAndFlush(ArchiveOperationItem.builder().batchId(batchId).fileId(file.getId())
                .itemKey(batchId + ":" + file.getId()).sourceFileName("original.txt")
                .targetFileName("archived.txt").sourceCategory("REPORT").targetCategory("CONTRACT")
                .sourcePath("files/original.txt").targetPath("contracts/archived.txt")
                .sourceEtag("source-etag").sourceSize(12L).targetEtag("target-etag").targetSize(12L)
                .preExecuteRevision(0L).postExecuteRevision(1L)
                .executionStatus(ArchiveOperationItemExecutionStatus.SUCCEEDED).build());
    }

    private void stubTarget(boolean exists, String etag) {
        when(minio.objectExists(isNull(), eq("contracts/archived.txt"))).thenReturn(exists);
        when(minio.statFile(isNull(), eq("contracts/archived.txt")))
                .thenReturn(new MinioStorageService.ObjectSnapshot(etag, 12));
        doNothing().when(minio).copyObject(anyString(), anyString());
        doNothing().when(minio).deleteFile(isNull(), anyString());
    }
}
