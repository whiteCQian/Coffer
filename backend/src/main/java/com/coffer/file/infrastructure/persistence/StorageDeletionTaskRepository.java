package com.coffer.file.infrastructure.persistence;

import com.coffer.file.domain.StorageDeletionStatus;
import com.coffer.file.domain.StorageDeletionTask;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StorageDeletionTaskRepository extends com.coffer.auth.infrastructure.OwnedRepository<StorageDeletionTask, Long> {

    @Query("""
            select t from StorageDeletionTask t
            where (t.status = :pending or t.status = :failed or (t.status = :running and t.nextAttemptAt <= :now))
              and (t.nextAttemptAt is null or t.nextAttemptAt <= :now)
            order by t.createdAt asc
            """)
    List<StorageDeletionTask> findDue(StorageDeletionStatus pending, StorageDeletionStatus failed,
                                      StorageDeletionStatus running, LocalDateTime now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from StorageDeletionTask t where t.id = :id")
    Optional<StorageDeletionTask> findByIdForUpdate(Long id);
}
