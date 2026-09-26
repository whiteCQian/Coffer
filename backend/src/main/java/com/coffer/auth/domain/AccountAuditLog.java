package com.coffer.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Account-level audit only; this record deliberately has no file or prompt fields. */
@Entity
@Table(name = "account_audit_log", indexes = @Index(name = "idx_account_audit_created_at", columnList = "created_at"))
@Getter
@NoArgsConstructor
public class AccountAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    @Column(name = "actor_username", nullable = false, length = 64)
    private String actorUsername;

    @Column(name = "target_user_id")
    private Long targetUserId;

    @Column(name = "action", nullable = false, length = 48)
    private String action;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public AccountAuditLog(Long actorUserId, String actorUsername, Long targetUserId, String action) {
        this.actorUserId = actorUserId;
        this.actorUsername = actorUsername;
        this.targetUserId = targetUserId;
        this.action = action;
        this.createdAt = LocalDateTime.now();
    }
}
