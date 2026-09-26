package com.coffer.inbox.infrastructure.persistence;

import com.coffer.inbox.domain.InboxImportRecord;
import com.coffer.inbox.domain.InboxImportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Persistence operations for durable inbox scan/import state. */
@Repository
public interface InboxImportRecordRepository extends com.coffer.auth.infrastructure.OwnedRepository<InboxImportRecord, Long> {

    Optional<InboxImportRecord> findBySnapshotKey(String snapshotKey);

    Optional<InboxImportRecord> findFirstByContentSha256AndStatusIn(
            String contentSha256, Collection<InboxImportStatus> statuses);

    List<InboxImportRecord> findTop50ByOrderByUpdatedAtDesc();

    long countByStatus(InboxImportStatus status);

    /**
     * Atomically claims a stable/retryable row. This remains safe if a second
     * application instance is accidentally started with the same inbox.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE InboxImportRecord r SET r.status = :targetStatus, "
            + "r.importStartedAt = :now, r.importFinishedAt = null, "
            + "r.attemptCount = r.attemptCount + 1, r.updatedAt = :now "
            + "WHERE r.id = :id AND r.ownerId = :ownerId AND r.status IN :claimableStatuses")
    int claimForImport(@Param("id") Long id,
                       @Param("ownerId") Long ownerId,
                       @Param("targetStatus") InboxImportStatus targetStatus,
                       @Param("claimableStatuses") Collection<InboxImportStatus> claimableStatuses,
                       @Param("now") LocalDateTime now);
}
