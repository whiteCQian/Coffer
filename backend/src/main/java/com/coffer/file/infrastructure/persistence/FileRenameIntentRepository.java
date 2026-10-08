package com.coffer.file.infrastructure.persistence;

import com.coffer.auth.infrastructure.OwnedRepository;
import com.coffer.file.domain.FileRenameIntent;
import com.coffer.file.domain.FileRenameIntentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FileRenameIntentRepository extends OwnedRepository<FileRenameIntent, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from FileRenameIntent i where i.id = :id")
    Optional<FileRenameIntent> lockById(String id);
    @Query("select i from FileRenameIntent i where i.status in :statuses "
            + "and i.nextAttemptAt <= :before order by i.nextAttemptAt")
    List<FileRenameIntent> findRecoverable(java.util.Collection<FileRenameIntentStatus> statuses,
                                           LocalDateTime before, Pageable page);
    @Query("select i from FileRenameIntent i where i.status = :prepared "
            + "or (i.status = :failed and (i.nextAttemptAt is null or i.nextAttemptAt <= :now)) "
            + "order by i.createdAt asc")
    List<FileRenameIntent> findReadyAtStartup(FileRenameIntentStatus prepared,
                                               FileRenameIntentStatus failed,
                                               LocalDateTime now, Pageable page);
    List<FileRenameIntent> findByStatusInOrderByCreatedAtAsc(
            java.util.Collection<FileRenameIntentStatus> statuses, Pageable page);
    List<FileRenameIntent> findTop100ByOrderByCreatedAtDesc();
    List<FileRenameIntent> findByOrderByCreatedAtDescIdDesc(Pageable page);
}
