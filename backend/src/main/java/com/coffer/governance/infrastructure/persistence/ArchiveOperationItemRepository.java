package com.coffer.governance.infrastructure.persistence;

import com.coffer.governance.domain.ArchiveOperationItem;
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

/** Persistence access for file-level archive operation audit items. */
@Repository
public interface ArchiveOperationItemRepository extends com.coffer.auth.infrastructure.OwnedRepository<ArchiveOperationItem, Long> {

    List<ArchiveOperationItem> findByBatchIdOrderByIdAsc(String batchId);

    List<ArchiveOperationItem> findByFileIdOrderByCreatedAtDesc(Long fileId);

    Optional<ArchiveOperationItem> findByBatchIdAndFileId(String batchId, Long fileId);

    Optional<ArchiveOperationItem> findByItemKey(String itemKey);

    List<ArchiveOperationItem> findByExecutionStatusIn(List<com.coffer.governance.domain.ArchiveOperationItemExecutionStatus> statuses);

    List<ArchiveOperationItem> findByRollbackStatusIn(List<com.coffer.governance.domain.ArchiveOperationItemRollbackStatus> statuses);

    @Query("""
            select i from ArchiveOperationItem i
            where (:batchId is null or i.batchId = :batchId)
              and (:fileId is null or i.fileId = :fileId)
              and (:status is null or i.executionStatus = :status)
            """
    )
    Page<ArchiveOperationItem> search(@Param("batchId") String batchId,
                                     @Param("fileId") Long fileId,
                                     @Param("status") com.coffer.governance.domain.ArchiveOperationItemExecutionStatus status,
                                     Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ArchiveOperationItem i where i.id = :id")
    Optional<ArchiveOperationItem> findByIdForUpdate(@Param("id") Long id);
}
