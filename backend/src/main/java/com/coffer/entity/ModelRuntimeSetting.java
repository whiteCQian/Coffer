package com.coffer.entity;

import com.coffer.governance.domain.GovernanceRunMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Per-owner model execution mode and validation snapshot. */
@Entity
@Table(name = "user_model_runtime_setting")
@Data
@lombok.EqualsAndHashCode(callSuper = false)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelRuntimeSetting extends com.coffer.auth.domain.TenantOwnedEntity {
    @Column(name = "inbox_snapshot_id", length = 36) private String inboxSnapshotId;

    public static final long SINGLETON_ID = 1L;

    @Id
    @Column(name = "id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "active_mode", nullable = false, length = 16)
    private GovernanceRunMode activeMode;

    @Column(name = "api_validated_at")
    private LocalDateTime apiValidatedAt;

    @Column(name = "local_validated_at")
    private LocalDateTime localValidatedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public LocalDateTime validatedAt(GovernanceRunMode mode) {
        return mode == GovernanceRunMode.LOCAL ? localValidatedAt : apiValidatedAt;
    }

    public void markValidated(GovernanceRunMode mode, LocalDateTime validatedAt) {
        if (mode == GovernanceRunMode.LOCAL) {
            localValidatedAt = validatedAt;
        } else {
            apiValidatedAt = validatedAt;
        }
    }

    public void clearValidations() {
        apiValidatedAt = null;
        localValidatedAt = null;
    }
}
