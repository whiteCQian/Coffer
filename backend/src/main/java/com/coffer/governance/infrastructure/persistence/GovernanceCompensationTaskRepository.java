package com.coffer.governance.infrastructure.persistence;

import com.coffer.governance.domain.GovernanceCompensationStatus;
import com.coffer.governance.domain.GovernanceCompensationTask;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface GovernanceCompensationTaskRepository extends com.coffer.auth.infrastructure.OwnedRepository<GovernanceCompensationTask, Long> {
    Optional<GovernanceCompensationTask> findByTaskKey(String taskKey);
    List<GovernanceCompensationTask> findByBatchIdOrderByCreatedAtDesc(String batchId);
    List<GovernanceCompensationTask> findByStatus(GovernanceCompensationStatus status);

    @Query("select t from GovernanceCompensationTask t where t.status in :statuses and (t.nextAttemptAt is null or t.nextAttemptAt <= :now) order by t.createdAt")
    List<GovernanceCompensationTask> findDue(@Param("statuses") List<GovernanceCompensationStatus> statuses,
                                              @Param("now") LocalDateTime now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from GovernanceCompensationTask t where t.id = :id")
    Optional<GovernanceCompensationTask> findByIdForUpdate(@Param("id") Long id);
}
