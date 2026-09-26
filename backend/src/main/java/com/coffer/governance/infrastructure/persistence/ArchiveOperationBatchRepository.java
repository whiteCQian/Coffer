package com.coffer.governance.infrastructure.persistence;

import com.coffer.governance.domain.ArchiveOperationBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Persistence access for archive operation batches. */
@Repository
public interface ArchiveOperationBatchRepository extends com.coffer.auth.infrastructure.OwnedRepository<ArchiveOperationBatch, Long> {

    Optional<ArchiveOperationBatch> findByBatchId(String batchId);

    Optional<ArchiveOperationBatch> findByRequestId(String requestId);

    boolean existsByRequestId(String requestId);

    List<ArchiveOperationBatch> findByPreviewIdOrderByCreatedAtDesc(String previewId);

    @Query("""
            select b from ArchiveOperationBatch b
            where (:batchId is null or lower(b.batchId) like lower(concat('%', :batchId, '%')))
              and (:fileId is null or exists (
                  select i.id from ArchiveOperationItem i
                  where i.batchId = b.batchId and i.fileId = :fileId
              ))
              and (:status is null or b.status = :status)
            """
    )
    Page<ArchiveOperationBatch> search(@Param("batchId") String batchId,
                                       @Param("fileId") Long fileId,
                                       @Param("status") com.coffer.governance.domain.ArchiveOperationBatchStatus status,
                                       Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ArchiveOperationBatch b where b.batchId = :batchId")
    Optional<ArchiveOperationBatch> findByBatchIdForUpdate(@Param("batchId") String batchId);
}
