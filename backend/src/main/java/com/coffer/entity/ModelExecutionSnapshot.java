package com.coffer.entity;

import com.coffer.auth.domain.TenantOwnedEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** Immutable, encrypted model destination consent captured before submission. */
@Entity @Table(name = "model_execution_snapshot")
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class ModelExecutionSnapshot extends TenantOwnedEntity {
    @Id @Column(length = 36) private String id;
    @Column(name = "configuration_version", nullable = false, length = 64) private String configurationVersion;
    @Column(name = "run_mode", nullable = false, length = 16) private String runMode;
    @Setter @Column(name = "encrypted_configuration", nullable = false, columnDefinition = "TEXT") private String encryptedConfiguration;
    @Column(name = "confirmed_at", nullable = false) private LocalDateTime confirmedAt;
    @Column(name = "purpose", nullable = false, length = 32) private String purpose;
}
