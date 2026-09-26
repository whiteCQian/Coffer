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

/** Persistent aggregate root for one confirmed archive operation batch. */
@Entity
@Table(name = "archive_operation_batch",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_archive_operation_batch_batch_id", columnNames = {"owner_id", "batch_id"}),
                @UniqueConstraint(name = "uk_archive_operation_batch_request_id", columnNames = {"owner_id", "request_id"})
        },
        indexes = {
                @Index(name = "idx_archive_operation_batch_status_created", columnList = "status,created_at"),
                @Index(name = "idx_archive_operation_batch_rollback_created", columnList = "rollback_status,created_at")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class ArchiveOperationBatch extends com.coffer.auth.domain.TenantOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Public stable ID used by APIs and audit URLs. */
    @Column(name = "batch_id", nullable = false, length = 64)
    private String batchId;

    @Column(name = "preview_id", length = 64)
    private String previewId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_source", nullable = false, length = 32)
    private ArchiveOperationSource source;

    /** Mode captured at confirmation time; it must not change during execution. */
    @Enumerated(EnumType.STRING)
    @Column(name = "run_mode", nullable = false, length = 16)
    private GovernanceRunMode runMode;

    /** Idempotency key for the confirm request. */
    @Column(name = "request_id", nullable = false, length = 128)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 32)
    private ArchiveOperationBatchStatus status = ArchiveOperationBatchStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "rollback_status", nullable = false, length = 32)
    private ArchiveOperationRollbackStatus rollbackStatus = ArchiveOperationRollbackStatus.NOT_REQUESTED;

    @Builder.Default
    @Column(name = "total_count", nullable = false)
    private int totalCount = 0;

    @Builder.Default
    @Column(name = "success_count", nullable = false)
    private int successCount = 0;

    @Builder.Default
    @Column(name = "failed_count", nullable = false)
    private int failedCount = 0;

    @Builder.Default
    @Column(name = "conflicted_count", nullable = false)
    private int conflictedCount = 0;

    @Builder.Default
    @Column(name = "skipped_count", nullable = false)
    private int skippedCount = 0;

    @Column(name = "failure_summary", columnDefinition = "TEXT")
    private String failureSummary;

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
