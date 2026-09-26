package com.coffer.inbox.domain;

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
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Durable record for one source-file snapshot discovered in the configured inbox.
 *
 * <p>The snapshot key is derived from normalized path, size and last-modified time.
 * The content hash is calculated only after the file is stable, so an interrupted
 * copy is never treated as a successfully imported file.</p>
 */
@Entity
@Table(name = "inbox_import_record", uniqueConstraints = @UniqueConstraint(
        name = "uk_inbox_import_record_snapshot_key", columnNames = {"owner_id", "snapshot_key"}), indexes = {
        @Index(name = "idx_inbox_import_record_status_seen", columnList = "status,last_seen_at"),
        @Index(name = "idx_inbox_import_record_content_sha256", columnList = "content_sha256"),
        @Index(name = "idx_inbox_import_record_source_path", columnList = "source_path")
})
@Data
@lombok.EqualsAndHashCode(callSuper = false)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboxImportRecord extends com.coffer.auth.domain.TenantOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "snapshot_key", nullable = false, length = 64)
    private String snapshotKey;

    @Column(name = "source_path", nullable = false, length = 1000)
    private String sourcePath;

    @Column(name = "source_file_name", nullable = false, length = 255)
    private String sourceFileName;

    @Column(name = "source_size", nullable = false)
    private Long sourceSize;

    @Column(name = "source_modified_at", nullable = false)
    private LocalDateTime sourceModifiedAt;

    @Column(name = "content_sha256", length = 64)
    private String contentSha256;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private InboxImportStatus status = InboxImportStatus.DISCOVERED;

    @Column(name = "stable_observations", nullable = false)
    @Builder.Default
    private Integer stableObservations = 1;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "file_id")
    private Long fileId;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "stable_since_at")
    private LocalDateTime stableSinceAt;

    @Column(name = "import_started_at")
    private LocalDateTime importStartedAt;

    @Column(name = "import_finished_at")
    private LocalDateTime importFinishedAt;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
