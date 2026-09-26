package com.coffer.governance;

import com.coffer.governance.api.dto.ArchiveOperationBatchSummaryResponse;
import com.coffer.governance.api.dto.ArchiveOperationItemResponse;
import com.coffer.governance.application.ArchiveOperationQueryService;
import com.coffer.governance.domain.ArchiveOperationBatch;
import com.coffer.governance.domain.ArchiveOperationBatchStatus;
import com.coffer.governance.domain.ArchiveOperationItem;
import com.coffer.governance.domain.ArchiveOperationItemExecutionStatus;
import com.coffer.governance.domain.ArchiveOperationItemExecutionStep;
import com.coffer.governance.domain.ArchiveOperationSource;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationBatchRepository;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationItemRepository;
import com.coffer.service.MinioStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** C08 query and JSON/CSV export coverage. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:archive_operation_query_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.flyway.locations=classpath:db/migration/h2",
        "minio.access-key=test-access-key",
        "minio.secret-key=test-secret-key"
})
class ArchiveOperationQueryTest extends com.coffer.auth.OwnerTestSupport {

    @Autowired
    private ArchiveOperationQueryService queryService;
    @Autowired
    private ArchiveOperationBatchRepository batchRepository;
    @Autowired
    private ArchiveOperationItemRepository itemRepository;

    @MockitoBean
    private MinioStorageService minioStorageService;

    @BeforeEach
    void cleanDatabase() {
        itemRepository.deleteAll();
        batchRepository.deleteAll();
    }

    @Test
    void searchesBatchesByFileAndStatus() {
        saveOperation("archive-c08-success", "1001", ArchiveOperationBatchStatus.SUCCEEDED,
                ArchiveOperationItemExecutionStatus.SUCCEEDED);
        saveOperation("archive-c08-failed", "1002", ArchiveOperationBatchStatus.FAILED,
                ArchiveOperationItemExecutionStatus.FAILED);

        Page<ArchiveOperationBatchSummaryResponse> byFile = queryService.searchBatches(
                null, 1001L, null, PageRequest.of(0, 20, Sort.by("createdAt")));
        Page<ArchiveOperationBatchSummaryResponse> byStatus = queryService.searchBatches(
                "archive-c08-failed", null, ArchiveOperationBatchStatus.FAILED,
                PageRequest.of(0, 20, Sort.by("createdAt")));

        assertThat(byFile.getContent()).extracting(ArchiveOperationBatchSummaryResponse::batchId)
                .containsExactly("archive-c08-success");
        assertThat(byStatus.getContent()).extracting(ArchiveOperationBatchSummaryResponse::batchId)
                .containsExactly("archive-c08-failed");
    }

    @Test
    void searchesFileLevelItemsAndExportsJsonAndCsv() {
        saveOperation("archive-c08-export", "2001", ArchiveOperationBatchStatus.FAILED,
                ArchiveOperationItemExecutionStatus.FAILED);

        Page<ArchiveOperationItemResponse> items = queryService.searchItems(
                "archive-c08-export", null, ArchiveOperationItemExecutionStatus.FAILED,
                PageRequest.of(0, 20, Sort.by("createdAt")));
        ArchiveOperationQueryService.ExportedOperationLedger json = queryService.export(
                "archive-c08-export", null, null, "json");
        ArchiveOperationQueryService.ExportedOperationLedger csv = queryService.export(
                "archive-c08-export", null, null, "csv");

        assertThat(items.getTotalElements()).isEqualTo(1);
        assertThat(items.getContent().get(0).failureCode()).isEqualTo("MINIO_IO_FAILED");
        assertThat(new String(json.content(), StandardCharsets.UTF_8))
                .contains("archive-c08-export", "source-2001.txt");
        assertThat(new String(csv.content(), StandardCharsets.UTF_8))
                .contains("batchId,fileId,sourceFileName", "archive-c08-export", "MINIO_IO_FAILED");
    }

    private void saveOperation(String batchId, String fileId, ArchiveOperationBatchStatus batchStatus,
                               ArchiveOperationItemExecutionStatus itemStatus) {
        ArchiveOperationBatch batch = batchRepository.saveAndFlush(ArchiveOperationBatch.builder()
                .batchId(batchId)
                .previewId("preview-" + batchId)
                .source(ArchiveOperationSource.PREVIEW_CONFIRMATION)
                .runMode(GovernanceRunMode.LOCAL)
                .requestId("request-" + batchId)
                .status(batchStatus)
                .totalCount(1)
                .successCount(itemStatus == ArchiveOperationItemExecutionStatus.SUCCEEDED ? 1 : 0)
                .failedCount(itemStatus == ArchiveOperationItemExecutionStatus.FAILED ? 1 : 0)
                .failureSummary(itemStatus == ArchiveOperationItemExecutionStatus.FAILED
                        ? "fileId=" + fileId + ": MinIO unavailable" : null)
                .build());
        itemRepository.saveAndFlush(ArchiveOperationItem.builder()
                .batchId(batch.getBatchId())
                .fileId(Long.valueOf(fileId))
                .itemKey("item-" + batchId)
                .sourceFileName("source-" + fileId + ".txt")
                .targetFileName("target-" + fileId + ".txt")
                .sourceCategory("REPORT")
                .targetCategory("CONTRACT")
                .sourcePath("files/source-" + fileId + ".txt")
                .targetPath("contracts/target-" + fileId + ".txt")
                .sourceEtag("etag-source-" + fileId)
                .sourceSize(12L)
                .executionStatus(itemStatus)
                .executionStep(itemStatus == ArchiveOperationItemExecutionStatus.SUCCEEDED
                        ? ArchiveOperationItemExecutionStep.COMPLETED
                        : ArchiveOperationItemExecutionStep.NONE)
                .attempts(itemStatus == ArchiveOperationItemExecutionStatus.FAILED ? 3 : 1)
                .failureCode(itemStatus == ArchiveOperationItemExecutionStatus.FAILED ? "MINIO_IO_FAILED" : null)
                .failureMessage(itemStatus == ArchiveOperationItemExecutionStatus.FAILED
                        ? "MinIO unavailable" : null)
                .build());
    }
}
