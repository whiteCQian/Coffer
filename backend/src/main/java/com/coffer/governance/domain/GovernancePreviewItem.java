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

/** File-level dry-run result and source snapshot. */
@Entity
@Table(name = "governance_preview_item",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_governance_preview_item_preview_file", columnNames = {"owner_id", "preview_id", "file_id"})
        },
        indexes = {
                @Index(name = "idx_governance_preview_item_preview_status", columnList = "preview_id,status"),
                @Index(name = "idx_governance_preview_item_file_created", columnList = "file_id,created_at")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class GovernancePreviewItem extends com.coffer.auth.domain.TenantOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "preview_id", nullable = false, length = 64)
    private String previewId;

    /** Scalar reference so preview history remains after file lifecycle changes. */
    @Column(name = "file_id", nullable = false)
    private Long fileId;

    /** File governance version observed when analysis started. */
    @Column(name = "source_revision", nullable = false)
    @Builder.Default
    private Long sourceRevision = 0L;

    @Column(name = "source_path", length = 500)
    private String sourcePath;

    @Column(name = "source_file_name", columnDefinition = "TEXT")
    private String sourceFileName;

    @Column(name = "source_category", length = 30)
    private String sourceCategory;

    @Column(name = "source_etag", length = 255)
    private String sourceEtag;

    @Column(name = "source_size")
    private Long sourceSize;

    @Column(name = "suggested_file_name", columnDefinition = "TEXT")
    private String suggestedFileName;

    @Column(name = "suggested_category", length = 30)
    private String suggestedCategory;

    @Column(name = "suggested_path", length = 500)
    private String suggestedPath;

    @Column(name = "suggested_summary", columnDefinition = "TEXT")
    private String suggestedSummary;

    /** Normalized JSON array; not a formal file-tag relation until confirmation. */
    @Column(name = "suggested_tags", columnDefinition = "TEXT")
    private String suggestedTags;

    @Column(name = "analysis_model", length = 128)
    private String analysisModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_mode", length = 16)
    private GovernanceRunMode analysisMode;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 32)
    private GovernancePreviewItemStatus status = GovernancePreviewItemStatus.PENDING;

    @Column(name = "skip_reason", length = 255)
    private String skipReason;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;
}
