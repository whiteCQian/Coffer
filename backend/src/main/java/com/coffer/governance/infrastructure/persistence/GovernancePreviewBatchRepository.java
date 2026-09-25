package com.coffer.governance.infrastructure.persistence;

import com.coffer.governance.domain.GovernancePreviewBatch;
import com.coffer.governance.domain.GovernancePreviewBatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GovernancePreviewBatchRepository extends JpaRepository<GovernancePreviewBatch, Long> {

    Optional<GovernancePreviewBatch> findByPreviewId(String previewId);

    Optional<GovernancePreviewBatch> findByRequestId(String requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from GovernancePreviewBatch b where b.previewId = :previewId")
    Optional<GovernancePreviewBatch> findByPreviewIdForUpdate(@Param("previewId") String previewId);

    List<GovernancePreviewBatch> findByStatusInAndExpiresAtBefore(
            Collection<GovernancePreviewBatchStatus> statuses, LocalDateTime time);
}
