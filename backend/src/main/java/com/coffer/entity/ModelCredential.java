package com.coffer.entity;

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

/** Encrypted API credentials for the locally configured model providers. */
@Entity
@Table(name = "user_model_credential")
@Data
@lombok.EqualsAndHashCode(callSuper = false)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelCredential extends com.coffer.auth.domain.TenantOwnedEntity {

    @Id
    @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 32, nullable = false)
    private ModelProvider provider;

    @Column(name = "encrypted_api_key", columnDefinition = "TEXT", nullable = false)
    private String encryptedApiKey;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
