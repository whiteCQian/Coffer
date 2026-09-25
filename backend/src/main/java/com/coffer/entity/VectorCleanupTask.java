package com.coffer.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "vector_cleanup_task", indexes = {
        @Index(name = "idx_vector_cleanup_due", columnList = "status,next_attempt_at")
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class VectorCleanupTask {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "file_id", nullable = false)
    private Long fileId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    @Builder.Default private VectorCleanupStatus status = VectorCleanupStatus.PENDING;
    @Column(nullable = false) @Builder.Default private int attempts = 0;
    @Column(name = "next_attempt_at") private LocalDateTime nextAttemptAt;
    @Column(name = "last_error", length = 10000) private String lastError;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    @PrePersist void onCreate() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate void onUpdate() { updatedAt = LocalDateTime.now(); }
}
