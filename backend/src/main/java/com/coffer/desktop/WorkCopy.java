package com.coffer.desktop;

import com.coffer.auth.domain.TenantOwnedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

/** Durable local editing session. Its UUID fixes the work directory and save idempotency key. */
@Entity @Table(name = "desktop_work_copy") @Getter @Setter
public class WorkCopy extends TenantOwnedEntity {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false) private Long fileId;
    @Column(nullable = false, length = 500) private String workKey;
    @Column(nullable = false, length = 500) private String beforeKey;
    @Column(nullable = false, columnDefinition = "TEXT") private String fileName;
    @Column(nullable = false) private long expectedRevision;
    @Column(nullable = false, length = 64) private String beforeSha256;
    @Column(nullable = false) private long beforeSize;
    @Column(nullable = false, length = 64) private String beforeModifiedTime;
    @Column(nullable = false, length = 255) private String beforeFileKey;
    @Column(nullable = false, length = 24) private String status;
    @Column(length = 64) private String errorCode;
    @Column(length = 36) private String operationId;
    private Long recoveredFileId;
    @Column(length = 64) private String confirmedSha256;
    @CreationTimestamp @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
    @UpdateTimestamp @Column(nullable = false) private LocalDateTime updatedAt;
}
