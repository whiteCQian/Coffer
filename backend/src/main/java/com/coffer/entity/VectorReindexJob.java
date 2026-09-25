package com.coffer.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity @Table(name = "vector_reindex_job")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class VectorReindexJob {
    @Id @Column(name = "job_id", length = 64) private String jobId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private VectorReindexStatus status;
    @Column(name = "total_count", nullable = false) @Builder.Default private int totalCount = 0;
    @Column(name = "processed_count", nullable = false) @Builder.Default private int processedCount = 0;
    @Column(name = "success_count", nullable = false) @Builder.Default private int successCount = 0;
    @Column(name = "failed_count", nullable = false) @Builder.Default private int failedCount = 0;
    @Column(name = "skipped_count", nullable = false) @Builder.Default private int skippedCount = 0;
    @Column(name = "error_summary", length = 10000) private String errorSummary;
    @Column(name = "started_at") private LocalDateTime startedAt;
    @Column(name = "finished_at") private LocalDateTime finishedAt;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @PrePersist void onCreate() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate void onUpdate() { updatedAt = LocalDateTime.now(); }
}
