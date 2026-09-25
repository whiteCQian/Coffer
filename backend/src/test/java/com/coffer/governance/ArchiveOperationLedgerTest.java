package com.coffer.governance;

import com.coffer.governance.domain.ArchiveOperationBatch;
import com.coffer.governance.domain.ArchiveOperationBatchStatus;
import com.coffer.governance.domain.ArchiveOperationItem;
import com.coffer.governance.domain.ArchiveOperationItemExecutionStatus;
import com.coffer.governance.domain.ArchiveOperationSource;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationBatchRepository;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the C03 ledger schema, mappings and idempotency constraints on H2. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:archive_operation_ledger_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.flyway.locations=classpath:db/migration/h2",
        "minio.access-key=test-access-key",
        "minio.secret-key=test-secret-key"
})
@ActiveProfiles("dev")
class ArchiveOperationLedgerTest {

    @Autowired
    private ArchiveOperationBatchRepository batchRepository;

    @Autowired
    private ArchiveOperationItemRepository itemRepository;

    @Test
    void persistsBatchAndItemWithIndependentExecutionAndRollbackStates() {
        ArchiveOperationBatch batch = batchRepository.saveAndFlush(ArchiveOperationBatch.builder()
                .batchId("batch-c03-001")
                .source(ArchiveOperationSource.PREVIEW_CONFIRMATION)
                .runMode(GovernanceRunMode.LOCAL)
                .requestId("confirm-c03-001")
                .totalCount(1)
                .build());

        ArchiveOperationItem item = itemRepository.saveAndFlush(ArchiveOperationItem.builder()
                .batchId(batch.getBatchId())
                .fileId(99L)
                .itemKey("item-c03-001")
                .sourcePath("files/inbox/a.txt")
                .targetPath("archive/other/a.txt")
                .build());

        assertThat(batch.getStatus()).isEqualTo(ArchiveOperationBatchStatus.PENDING);
        assertThat(batch.getRollbackStatus().name()).isEqualTo("NOT_REQUESTED");
        assertThat(item.getExecutionStatus()).isEqualTo(ArchiveOperationItemExecutionStatus.PENDING);
        assertThat(itemRepository.findByBatchIdOrderByIdAsc(batch.getBatchId()))
                .extracting(ArchiveOperationItem::getId)
                .containsExactly(item.getId());
    }

    @Test
    void rejectsDuplicateConfirmRequestIdAndDuplicateItemKey() {
        batchRepository.saveAndFlush(ArchiveOperationBatch.builder()
                .batchId("batch-c03-002")
                .source(ArchiveOperationSource.PREVIEW_CONFIRMATION)
                .runMode(GovernanceRunMode.API)
                .requestId("confirm-c03-duplicate")
                .build());

        assertThatThrownBy(() -> batchRepository.saveAndFlush(ArchiveOperationBatch.builder()
                .batchId("batch-c03-003")
                .source(ArchiveOperationSource.PREVIEW_CONFIRMATION)
                .runMode(GovernanceRunMode.API)
                .requestId("confirm-c03-duplicate")
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class);

        itemRepository.saveAndFlush(ArchiveOperationItem.builder()
                .batchId("batch-c03-002")
                .fileId(100L)
                .itemKey("item-c03-duplicate")
                .build());

        assertThatThrownBy(() -> itemRepository.saveAndFlush(ArchiveOperationItem.builder()
                .batchId("batch-c03-004")
                .fileId(101L)
                .itemKey("item-c03-duplicate")
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
