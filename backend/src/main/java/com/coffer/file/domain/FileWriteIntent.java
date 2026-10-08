package com.coffer.file.domain;

import com.coffer.auth.domain.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/** An intent committed before any object I/O; never store a local absolute path. */
@Entity
@Table(name = "file_write_intent",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_file_write_intent_task", columnNames = {"owner_id", "task_id"})
        },
        indexes = @Index(name = "idx_file_write_intent_due", columnList = "owner_id,status,next_attempt_at"))
@Getter
@Setter
public class FileWriteIntent extends TenantOwnedEntity {
    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;

    @Column(name = "file_name", nullable = false, columnDefinition = "TEXT")
    private String fileName;

    @Column(name = "file_type", length = 50)
    private String fileType;

    @Column(name = "content_type", length = 255)
    private String contentType;

    @Column(name = "declared_size", nullable = false)
    private long declaredSize;

    @Column(name = "content_sha256", length = 64)
    private String contentSha256;

    @Column(name = "model_snapshot_id", length = 36)
    private String modelSnapshotId;

    @Column(name = "file_id")
    private Long fileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private FileWriteIntentStatus status = FileWriteIntentStatus.PREPARED;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "retention_until")
    private LocalDateTime retentionUntil;

    @Column(name = "discarded_at")
    private LocalDateTime discardedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
