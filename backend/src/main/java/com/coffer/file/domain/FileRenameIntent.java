package com.coffer.file.domain;

import com.coffer.auth.domain.TenantOwnedEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

/** A committed rename request; its final status and file update commit atomically. */
@Entity @Table(name = "file_rename_intent")
@Getter @Setter @NoArgsConstructor
public class FileRenameIntent extends TenantOwnedEntity {
    @Id @Column(length = 64) private String id;
    @Column(name = "file_id", nullable = false) private Long fileId;
    @Column(name = "expected_revision", nullable = false) private Long expectedRevision;
    @Column(name = "before_name", nullable = false, columnDefinition = "TEXT") private String beforeName;
    @Column(name = "after_name", nullable = false, columnDefinition = "TEXT") private String afterName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private FileRenameIntentStatus status;
    @Column(name = "error_code", length = 40) private String errorCode;
    @Column(name = "attempts", nullable = false) private int attempts;
    @Column(name = "next_attempt_at") private LocalDateTime nextAttemptAt;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
}
