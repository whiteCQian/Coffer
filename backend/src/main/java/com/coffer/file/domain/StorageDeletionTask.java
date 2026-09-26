package com.coffer.file.domain;

import com.coffer.auth.domain.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/** Durable cleanup intent for object data after a user's file record is deleted. */
@Entity
@Table(name = "storage_deletion_task",
        uniqueConstraints = @UniqueConstraint(name = "uk_storage_deletion_task_key", columnNames = {"owner_id", "task_key"}),
        indexes = @Index(name = "idx_storage_deletion_due", columnList = "owner_id,status,next_attempt_at"))
@Getter
@Setter
@NoArgsConstructor
public class StorageDeletionTask extends TenantOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_key", nullable = false, length = 160)
    private String taskKey;

    @Column(name = "file_id", nullable = false)
    private Long fileId;

    @Column(name = "object_path", nullable = false, length = 500)
    private String objectPath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StorageDeletionStatus status = StorageDeletionStatus.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
