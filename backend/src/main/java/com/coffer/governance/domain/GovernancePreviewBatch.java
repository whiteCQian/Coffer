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

/** Persistent aggregate root for one dry-run governance preview. */
@Entity
@Table(name = "governance_preview_batch",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_governance_preview_batch_preview_id", columnNames = {"owner_id", "preview_id"}),
                @UniqueConstraint(name = "uk_governance_preview_batch_request_id", columnNames = {"owner_id", "request_id"})
        },
        indexes = {
                @Index(name = "idx_governance_preview_batch_status_expires", columnList = "status,expires_at"),
                @Index(name = "idx_governance_preview_batch_created", columnList = "created_at")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class GovernancePreviewBatch extends com.coffer.auth.domain.TenantOwnedEntity {
    @Column(name = "model_snapshot_id", length = 36) private String modelSnapshotId;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Public stable ID used by preview APIs and later confirmation requests. */
    @Column(name = "preview_id", nullable = false, length = 64)
    private String previewId;

    @Enumerated(EnumType.STRING)
    @Column(name = "preview_source", nullable = false, length = 32)
    private GovernancePreviewSource source;

    /** Run mode captured when this analysis starts. */
    @Enumerated(EnumType.STRING)
    @Column(name = "run_mode", nullable = false, length = 16)
    private GovernanceRunMode runMode;

    /** Idempotency key for preview generation. */
    @Column(name = "request_id", nullable = false, length = 128)
    private String requestId;

    @Builder.Default
    @Column(name = "created_by", nullable = false, length = 32)
    private String createdBy = "LOCAL_USER";

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 32)
    private GovernancePreviewBatchStatus status = GovernancePreviewBatchStatus.ANALYZING;

    @Builder.Default
    @Column(name = "total_count", nullable = false)
    private int totalCount = 0;

    @Builder.Default
    @Column(name = "ready_count", nullable = false)
    private int readyCount = 0;

    @Builder.Default
    @Column(name = "failed_count", nullable = false)
    private int failedCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;
}
