package com.coffer.entity;

import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.model.runtime.ModelRuntimeCapability;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** User-managed endpoint overrides for each runtime mode and model capability. */
@Entity
@Table(name = "model_runtime_endpoint", uniqueConstraints = @UniqueConstraint(
        name = "uk_model_runtime_endpoint_mode_capability",
        columnNames = {"run_mode", "capability"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelRuntimeEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_mode", nullable = false, length = 16)
    private GovernanceRunMode runMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "capability", nullable = false, length = 16)
    private ModelRuntimeCapability capability;

    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    @Column(name = "model_name", nullable = false, length = 128)
    private String modelName;

    @Column(name = "encrypted_api_key", columnDefinition = "TEXT")
    private String encryptedApiKey;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
