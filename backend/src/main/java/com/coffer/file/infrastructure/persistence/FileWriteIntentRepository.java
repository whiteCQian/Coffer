package com.coffer.file.infrastructure.persistence;

import com.coffer.file.domain.FileWriteIntent;
import com.coffer.file.domain.FileWriteIntentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FileWriteIntentRepository extends com.coffer.auth.infrastructure.OwnedRepository<FileWriteIntent, String> {
    boolean existsByObjectKeyAndStatusIn(String objectKey, List<FileWriteIntentStatus> statuses);
    Optional<FileWriteIntent> findFirstByObjectKeyAndKindAndStatus(
            String objectKey, String kind, FileWriteIntentStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from FileWriteIntent i where i.id = :id")
    Optional<FileWriteIntent> lockById(String id);

    @Query("select i from FileWriteIntent i where i.status in :statuses "
            + "and (i.nextAttemptAt is null or i.nextAttemptAt <= :now) "
            + "and (i.leaseUntil is null or i.leaseUntil <= :now) order by i.createdAt asc")
    List<FileWriteIntent> findDue(List<FileWriteIntentStatus> statuses, LocalDateTime now, Pageable pageable);

    @Query("select i from FileWriteIntent i where i.status in :interruptedStatuses "
            + "or (i.status = :failedStatus and (i.nextAttemptAt is null or i.nextAttemptAt <= :now)) "
            + "order by i.createdAt asc")
    List<FileWriteIntent> findReadyAtStartup(List<FileWriteIntentStatus> interruptedStatuses,
                                             FileWriteIntentStatus failedStatus, LocalDateTime now,
                                             Pageable pageable);

    List<FileWriteIntent> findByStatusInOrderByCreatedAtDesc(List<FileWriteIntentStatus> statuses, Pageable pageable);
    List<FileWriteIntent> findByStatusInOrderByCreatedAtDescIdDesc(List<FileWriteIntentStatus> statuses, Pageable pageable);
}
