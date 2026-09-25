package com.coffer.governance.infrastructure.persistence;

import com.coffer.governance.domain.ArchiveObjectNameAllocatorLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ArchiveObjectNameAllocatorLockRepository
        extends JpaRepository<ArchiveObjectNameAllocatorLock, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select lockRow from ArchiveObjectNameAllocatorLock lockRow where lockRow.id = :id")
    Optional<ArchiveObjectNameAllocatorLock> findByIdForUpdate(@Param("id") Long id);
}
