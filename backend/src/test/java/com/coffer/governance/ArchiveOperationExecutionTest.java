package com.coffer.governance;

import com.coffer.file.domain.CategoryType;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.governance.application.ArchiveOperationService;
import com.coffer.governance.domain.ArchiveOperationBatch;
import com.coffer.governance.domain.ArchiveOperationBatchStatus;
import com.coffer.governance.domain.ArchiveOperationItem;
import com.coffer.governance.domain.ArchiveOperationItemExecutionStatus;
import com.coffer.governance.domain.ArchiveOperationSource;
import com.coffer.governance.domain.GovernancePreviewBatch;
import com.coffer.governance.domain.GovernancePreviewBatchStatus;
import com.coffer.governance.domain.GovernancePreviewItem;
import com.coffer.governance.domain.GovernancePreviewItemStatus;
import com.coffer.governance.domain.GovernancePreviewSource;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationBatchRepository;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationItemRepository;
import com.coffer.governance.infrastructure.persistence.GovernancePreviewBatchRepository;
import com.coffer.governance.infrastructure.persistence.GovernancePreviewItemRepository;
import com.coffer.service.MinioStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/** C07 integration coverage for copy, metadata commit, per-item failure and retry state. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:archive_operation_execution_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.flyway.locations=classpath:db/migration/h2",
        "minio.access-key=test-access-key",
        "minio.secret-key=test-secret-key"
})
class ArchiveOperationExecutionTest extends com.coffer.auth.OwnerTestSupport {

    @Autowired
    private ArchiveOperationService archiveOperationService;
    @Autowired
    private FileMetadataRepository fileMetadataRepository;
    @Autowired
    private ArchiveOperationBatchRepository batchRepository;
    @Autowired
    private ArchiveOperationItemRepository itemRepository;
    @Autowired
    private GovernancePreviewBatchRepository previewBatchRepository;
    @Autowired
    private GovernancePreviewItemRepository previewItemRepository;

    @MockitoBean
    private MinioStorageService minioStorageService;

    @BeforeEach
    void cleanDatabase() {
        itemRepository.deleteAll();
        batchRepository.deleteAll();
        previewItemRepository.deleteAll();
        previewBatchRepository.deleteAll();
        fileMetadataRepository.deleteAll();
    }

    @Test
    void executesCopyThenCommitsFormalMetadataAndLedger() {
        FileMetadata metadata = saveFile("原始.txt", "files/source.txt", CategoryType.REPORT, 0L);
        String batchId = "archive-c07-success";
        seedOperation(batchId, "preview-c07-success", metadata, "整理后.txt",
                "contracts/整理后.txt", CategoryType.CONTRACT, "etag-source");
        when(minioStorageService.statFile(isNull(), anyString()))
                .thenAnswer(invocation -> {
                    String path = invocation.getArgument(1, String.class);
                    return path.equals("files/source.txt")
                            ? new MinioStorageService.ObjectSnapshot("etag-source", 12)
                            : new MinioStorageService.ObjectSnapshot("etag-target", 12);
                });
        when(minioStorageService.objectExists(isNull(), eq("contracts/整理后.txt"))).thenReturn(false);
        doNothing().when(minioStorageService).copyObject(anyString(), anyString());
        doNothing().when(minioStorageService).deleteFile(isNull(), anyString());

        archiveOperationService.executeBatch(batchId);

        FileMetadata saved = fileMetadataRepository.findById(metadata.getId()).orElseThrow();
        ArchiveOperationItem operationItem = itemRepository.findByBatchIdOrderByIdAsc(batchId).get(0);
        ArchiveOperationBatch batch = batchRepository.findByBatchId(batchId).orElseThrow();
        assertThat(saved.getStoragePath()).isEqualTo("contracts/整理后.txt");
        assertThat(saved.getFileName()).isEqualTo("整理后.txt");
        assertThat(saved.getCategory()).isEqualTo(CategoryType.CONTRACT);
        assertThat(saved.getSummary()).isEqualTo("整理摘要");
        assertThat(saved.getRevision()).isEqualTo(1L);
        assertThat(saved.isArchived()).isTrue();
        assertThat(operationItem.getExecutionStatus()).isEqualTo(ArchiveOperationItemExecutionStatus.SUCCEEDED);
        assertThat(operationItem.getPostExecuteRevision()).isEqualTo(1L);
        assertThat(batch.getStatus()).isEqualTo(ArchiveOperationBatchStatus.SUCCEEDED);
        assertThat(batch.getSuccessCount()).isEqualTo(1);
    }

    @Test
    void oneFailedItemDoesNotStopTheOtherItems() {
        FileMetadata success = saveFile("success.txt", "files/success.txt", CategoryType.REPORT, 0L);
        FileMetadata failed = saveFile("failed.txt", "files/failed.txt", CategoryType.REPORT, 0L);
        String batchId = "archive-c07-partial";
        seedOperation(batchId, "preview-c07-partial", success, "success-归档.txt",
                "contracts/success-归档.txt", CategoryType.CONTRACT, "etag-success");
        seedOperation(batchId, "preview-c07-partial", failed, "failed-归档.txt",
                "contracts/failed-归档.txt", CategoryType.CONTRACT, "etag-failed");
        when(minioStorageService.statFile(isNull(), anyString()))
                .thenAnswer(invocation -> new MinioStorageService.ObjectSnapshot("etag-" +
                        invocation.getArgument(1, String.class).replace("files/", "").replace(".txt", ""), 12));
        when(minioStorageService.objectExists(isNull(), anyString())).thenReturn(false);
        doNothing().when(minioStorageService).copyObject("files/success.txt", "contracts/success-归档.txt");
        doThrow(new RuntimeException("copy failed"))
                .when(minioStorageService).copyObject("files/failed.txt", "contracts/failed-归档.txt");

        archiveOperationService.executeBatch(batchId);

        ArchiveOperationBatch batch = batchRepository.findByBatchId(batchId).orElseThrow();
        assertThat(itemRepository.findByBatchIdOrderByIdAsc(batchId))
                .extracting(ArchiveOperationItem::getExecutionStatus)
                .containsExactlyInAnyOrder(ArchiveOperationItemExecutionStatus.SUCCEEDED,
                        ArchiveOperationItemExecutionStatus.FAILED);
        assertThat(batch.getStatus()).isEqualTo(ArchiveOperationBatchStatus.PARTIAL_FAILED);
        assertThat(batch.getSuccessCount()).isEqualTo(1);
        assertThat(batch.getFailedCount()).isEqualTo(1);
        assertThat(fileMetadataRepository.findById(success.getId()).orElseThrow().isArchived()).isTrue();
        assertThat(fileMetadataRepository.findById(failed.getId()).orElseThrow().isArchived()).isFalse();
    }

    @Test
    void changedRevisionBecomesConflictWithoutOverwritingFormalState() {
        FileMetadata metadata = saveFile("changed.txt", "files/changed.txt", CategoryType.REPORT, 1L);
        String batchId = "archive-c07-conflict";
        seedOperation(batchId, "preview-c07-conflict", metadata, "should-not-move.txt",
                "contracts/should-not-move.txt", CategoryType.CONTRACT, "etag-source");
        ArchiveOperationItem staleItem = itemRepository.findByBatchIdOrderByIdAsc(batchId).get(0);
        staleItem.setExpectedRevision(0L);
        itemRepository.saveAndFlush(staleItem);
        when(minioStorageService.statFile(isNull(), eq("files/changed.txt")))
                .thenReturn(new MinioStorageService.ObjectSnapshot("etag-source", 12));

        archiveOperationService.executeBatch(batchId);

        ArchiveOperationItem operationItem = itemRepository.findByBatchIdOrderByIdAsc(batchId).get(0);
        FileMetadata saved = fileMetadataRepository.findById(metadata.getId()).orElseThrow();
        assertThat(operationItem.getExecutionStatus()).isEqualTo(ArchiveOperationItemExecutionStatus.CONFLICTED);
        assertThat(operationItem.getFailureCode()).isEqualTo("EXECUTION_CONFLICT");
        assertThat(saved.getStoragePath()).isEqualTo("files/changed.txt");
        assertThat(saved.getFileName()).isEqualTo("changed.txt");
        assertThat(saved.getRevision()).isEqualTo(1L);
    }

    private FileMetadata saveFile(String name, String path, CategoryType category, long revision) {
        return fileMetadataRepository.saveAndFlush(FileMetadata.builder()
                .fileName(name)
                .fileSize(12L)
                .fileType("txt")
                .storagePath(path)
                .status(FileStatus.COMPLETED)
                .category(category)
                .summary("旧摘要")
                .revision(revision)
                .contentEtag("etag-source")
                .archived(false)
                .build());
    }

    private void seedOperation(String batchId, String previewId, FileMetadata metadata,
                                String targetName, String targetPath, CategoryType targetCategory,
                                String sourceEtag) {
        if (previewBatchRepository.findByPreviewId(previewId).isEmpty()) {
            previewBatchRepository.saveAndFlush(GovernancePreviewBatch.builder()
                    .previewId(previewId)
                    .source(GovernancePreviewSource.UPLOAD)
                    .runMode(GovernanceRunMode.LOCAL)
                    .requestId("preview-request-" + batchId)
                    .status(GovernancePreviewBatchStatus.CONFIRMED)
                    .totalCount(1)
                    .readyCount(1)
                    .expiresAt(java.time.LocalDateTime.now().plusHours(1))
                    .build());
        }
        previewItemRepository.saveAndFlush(GovernancePreviewItem.builder()
                .previewId(previewId)
                .fileId(metadata.getId())
                .sourceRevision(metadata.getRevision())
                .sourcePath(metadata.getStoragePath())
                .sourceFileName(metadata.getFileName())
                .sourceCategory(metadata.getCategory().name())
                .sourceEtag(sourceEtag)
                .sourceSize(metadata.getFileSize())
                .suggestedFileName(targetName)
                .suggestedCategory(targetCategory.name())
                .suggestedPath(targetPath)
                .suggestedSummary("整理摘要")
                .suggestedTags("[]")
                .status(GovernancePreviewItemStatus.CONFIRMED)
                .build());
        ArchiveOperationBatch batch = batchRepository.findByBatchId(batchId).orElseGet(() ->
                batchRepository.saveAndFlush(ArchiveOperationBatch.builder()
                        .batchId(batchId)
                        .previewId(previewId)
                        .source(ArchiveOperationSource.PREVIEW_CONFIRMATION)
                        .runMode(GovernanceRunMode.LOCAL)
                        .requestId("operation-request-" + batchId)
                        .totalCount(0)
                        .build()));
        itemRepository.saveAndFlush(ArchiveOperationItem.builder()
                .batchId(batchId)
                .fileId(metadata.getId())
                .itemKey("item-" + batchId + "-" + metadata.getId())
                .expectedRevision(metadata.getRevision())
                .sourceFileName(metadata.getFileName())
                .targetFileName(targetName)
                .sourceCategory(metadata.getCategory().name())
                .targetCategory(targetCategory.name())
                .sourcePath(metadata.getStoragePath())
                .targetPath(targetPath)
                .sourceEtag(sourceEtag)
                .sourceSize(metadata.getFileSize())
                .build());
        batch.setTotalCount(itemRepository.findByBatchIdOrderByIdAsc(batchId).size());
        batchRepository.saveAndFlush(batch);
    }
}
