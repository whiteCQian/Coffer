package com.coffer.governance.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "governance_compensation_task",
        uniqueConstraints = @UniqueConstraint(name = "uk_governance_compensation_task_key", columnNames = "task_key"),
        indexes = {
                @Index(name = "idx_governance_compensation_due", columnList = "status,next_attempt_at"),
                @Index(name = "idx_governance_compensation_batch", columnList = "batch_id,created_at")
        })
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class GovernanceCompensationTask {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "task_key", nullable = false, length = 160) private String taskKey;
    @Column(name = "batch_id", nullable = false, length = 64) private String batchId;
    @Column(name = "item_id", nullable = false) private Long itemId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) private GovernanceCompensationAction action;
    @Enumerated(EnumType.STRING) @Builder.Default @Column(nullable = false, length = 20)
    private GovernanceCompensationStatus status = GovernanceCompensationStatus.PENDING;
    @Column(name = "object_path", length = 500) private String objectPath;
    @Builder.Default @Column(nullable = false) private int attempts = 0;
    @Column(name = "next_attempt_at") private LocalDateTime nextAttemptAt;
    @Column(name = "last_error", columnDefinition = "TEXT") private String lastError;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @Column(name = "finished_at") private LocalDateTime finishedAt;
}
