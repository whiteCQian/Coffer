package com.coffer.model.runtime;

import com.coffer.auth.domain.TenantOwnedEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** One owner, file content revision and exact model endpoint authorization; never stores body text. */
@Entity @Table(name = "file_model_approval")
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class FileModelApproval extends TenantOwnedEntity {
    @Id @Column(length = 36) private String id;
    @Column(name = "file_id", nullable = false) private Long fileId;
    @Column(name = "file_revision", nullable = false) private Long fileRevision;
    @Column(name = "content_sha256", nullable = false, length = 64) private String contentSha256;
    @Column(name = "configuration_version", nullable = false, length = 64) private String configurationVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private ModelRuntimeCapability capability;
    @Column(name = "endpoint_url", nullable = false, length = 1000) private String endpointUrl;
    @Column(name = "model_name", nullable = false, length = 255) private String modelName;
    @Column(nullable = false, length = 16) private String risk;
    @Column(name = "confirmed_at", nullable = false) private LocalDateTime confirmedAt;
}
