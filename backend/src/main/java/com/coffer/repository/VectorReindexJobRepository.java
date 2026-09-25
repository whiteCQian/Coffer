package com.coffer.repository;
import com.coffer.entity.VectorReindexJob;
import org.springframework.data.jpa.repository.JpaRepository;
import com.coffer.entity.VectorReindexStatus;
import java.util.List;
public interface VectorReindexJobRepository extends JpaRepository<VectorReindexJob, String> {
    List<VectorReindexJob> findByStatus(VectorReindexStatus status);
}
