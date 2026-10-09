package com.coffer.file.domain;

import com.coffer.auth.domain.TenantOwnedEntity;
import com.coffer.governance.domain.GovernanceRunMode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/** Durable replacement intent: the old formal object stays untouched until DB commit. */
@Entity
@Table(name = "work_save_intent", uniqueConstraints = {
        @UniqueConstraint(name = "uk_work_save_target", columnNames = {"owner_id", "target_key"}),
        @UniqueConstraint(name = "uk_work_save_task", columnNames = {"owner_id", "task_id"})
}, indexes = @Index(name = "idx_work_save_due", columnList = "owner_id,status,next_attempt_at"))
@Getter @Setter
public class WorkSaveIntent extends TenantOwnedEntity {
    @Id @Column(name = "id", length = 36) private String id;
    @Column(name = "file_id", nullable = false) private Long fileId;
    @Column(name = "expected_revision", nullable = false) private long expectedRevision;
    @Column(name = "before_key", nullable = false, length = 500) private String beforeKey;
    @Column(name = "before_sha256", nullable = false, length = 64) private String beforeSha256;
    @Column(name = "before_size") private Long beforeSize;
    @Column(name = "before_modified_time", length = 64) private String beforeModifiedTime;
    @Column(name = "before_file_key", length = 255) private String beforeFileKey;
    @Column(name = "preserve_only", nullable = false) private boolean preserveOnly;
    @Column(name = "target_key", nullable = false, length = 500) private String targetKey;
    @Column(name = "target_size", nullable = false) private long targetSize;
    @Column(name = "request_sha256", nullable = false, length = 64) private String requestSha256;
    @Column(name = "target_sha256", length = 64) private String targetSha256;
    @Column(name = "target_etag", length = 255) private String targetEtag;
    @Column(name = "recovered_file_id") private Long recoveredFileId;
    @Column(name = "task_id", nullable = false, length = 64) private String taskId;
    @Column(name = "file_name", nullable = false, columnDefinition = "TEXT") private String fileName;
    @Column(name = "file_type", length = 50) private String fileType;
    @Column(name = "model_snapshot_id", length = 36) private String modelSnapshotId;
    @Enumerated(EnumType.STRING)
    @Column(name = "run_mode", nullable = false, length = 16) private GovernanceRunMode runMode;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24) private WorkSaveStatus status = WorkSaveStatus.PREPARED;
    @Column(name = "attempts", nullable = false) private int attempts;
    @Column(name = "next_attempt_at") private LocalDateTime nextAttemptAt;
    @Column(name = "last_error_code", length = 64) private String lastErrorCode;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
}
