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

/** Persistent singleton containing the active model execution mode and validations. */
@Entity
@Table(name = "model_runtime_setting")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelRuntimeSetting {

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
