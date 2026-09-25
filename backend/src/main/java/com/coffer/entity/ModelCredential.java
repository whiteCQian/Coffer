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
@Table(name = "model_credential")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelCredential {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 32, nullable = false)
    private ModelProvider provider;

    @Column(name = "encrypted_api_key", columnDefinition = "TEXT", nullable = false)
    private String encryptedApiKey;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
