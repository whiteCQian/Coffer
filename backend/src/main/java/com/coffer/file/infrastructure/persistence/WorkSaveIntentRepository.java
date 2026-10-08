package com.coffer.file.infrastructure.persistence;

import com.coffer.auth.infrastructure.OwnedRepository;
import com.coffer.file.domain.WorkSaveIntent;
import com.coffer.file.domain.WorkSaveStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WorkSaveIntentRepository extends OwnedRepository<WorkSaveIntent, String> {
    boolean existsByTargetKey(String targetKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from WorkSaveIntent i where i.id = :id")
    Optional<WorkSaveIntent> lockById(String id);
    @Query("select i from WorkSaveIntent i where i.status in :statuses "
            + "and (i.nextAttemptAt is null or i.nextAttemptAt <= :now) order by i.createdAt asc")
    List<WorkSaveIntent> findDue(List<WorkSaveStatus> statuses, LocalDateTime now, Pageable page);
    @Query("select i from WorkSaveIntent i where i.status in :statuses "
            + "and (i.attempts = 0 or i.nextAttemptAt is null or i.nextAttemptAt <= :now) "
            + "order by i.createdAt asc")
    List<WorkSaveIntent> findReadyAtStartup(List<WorkSaveStatus> statuses, LocalDateTime now, Pageable page);
    List<WorkSaveIntent> findByStatusInOrderByCreatedAtAsc(List<WorkSaveStatus> statuses, Pageable page);
    List<WorkSaveIntent> findTop100ByOrderByCreatedAtDesc();
    List<WorkSaveIntent> findByOrderByCreatedAtDescIdDesc(Pageable page);
}
