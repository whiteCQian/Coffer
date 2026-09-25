package com.coffer.repository;

import com.coffer.entity.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface VectorCleanupTaskRepository extends JpaRepository<VectorCleanupTask, Long> {
    List<VectorCleanupTask> findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(VectorCleanupStatus status, LocalDateTime now, Pageable pageable);
    boolean existsByFileIdAndStatusNot(Long fileId, VectorCleanupStatus status);
}
