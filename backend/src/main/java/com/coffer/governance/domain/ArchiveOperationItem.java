package com.coffer.governance.domain;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/** One auditable file-level operation belonging to an archive batch. */
@Entity
@Table(name = "archive_operation_item",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_archive_operation_item_item_key", columnNames = "item_key"),
                @UniqueConstraint(name = "uk_archive_operation_item_batch_file", columnNames = {"batch_id", "file_id"})
        },
        indexes = {
                @Index(name = "idx_archive_operation_item_batch_status", columnList = "batch_id,execution_status"),
                @Index(name = "idx_archive_operation_item_file_created", columnList = "file_id,created_at"),
                @Index(name = "idx_archive_operation_item_rollback_status", columnList = "rollback_status,next_attempt_at")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class ArchiveOperationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false, length = 64)
    private String batchId;

    /** Kept as a scalar reference so deleting a file never deletes its audit history. */
    @Column(name = "file_id", nullable = false)
    private Long fileId;

    /** Stable idempotency key for this exact file operation and snapshot. */
    @Column(name = "item_key", nullable = false, length = 128)
    private String itemKey;

    @Builder.Default
    @Column(name = "expected_revision", nullable = false)
    private Long expectedRevision = 0L;

    @Column(name = "source_file_name", columnDefinition = "TEXT")
    private String sourceFileName;

    @Column(name = "target_file_name", columnDefinition = "TEXT")
    private String targetFileName;

    @Column(name = "source_category", length = 30)
    private String sourceCategory;

    @Column(name = "target_category", length = 30)
    private String targetCategory;

    @Column(name = "source_path", length = 500)
    private String sourcePath;

    @Column(name = "target_path", length = 500)
    private String targetPath;

    @Column(name = "source_etag", length = 255)
    private String sourceEtag;

    @Column(name = "source_size")
    private Long sourceSize;

    @Column(name = "target_etag", length = 255)
    private String targetEtag;

    @Column(name = "target_size")
    private Long targetSize;

    @Column(name = "pre_execute_revision")
    private Long preExecuteRevision;

    @Column(name = "post_execute_revision")
    private Long postExecuteRevision;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "execution_status", nullable = false, length = 32)
    private ArchiveOperationItemExecutionStatus executionStatus = ArchiveOperationItemExecutionStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "execution_step", nullable = false, length = 32)
    private ArchiveOperationItemExecutionStep executionStep = ArchiveOperationItemExecutionStep.NONE;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "rollback_status", nullable = false, length = 32)
    private ArchiveOperationItemRollbackStatus rollbackStatus = ArchiveOperationItemRollbackStatus.NOT_REQUESTED;

    @Builder.Default
    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "rollback_started_at")
    private LocalDateTime rollbackStartedAt;

    @Column(name = "rollback_finished_at")
    private LocalDateTime rollbackFinishedAt;
}
