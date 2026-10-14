package com.coffer.model.runtime;

import com.coffer.governance.domain.GovernanceRunMode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** Instance-wide connection, separate from all user-owned model credentials. */
@Entity @Table(name = "admin_embedding_setting") @Getter @Setter
public class AdminEmbeddingSetting {
    @Id private Long id = 1L;
    @Enumerated(EnumType.STRING) @Column(name = "run_mode", nullable = false) private GovernanceRunMode mode = GovernanceRunMode.API;
    @Column(name = "base_url", nullable = false) private String baseUrl = "";
    @Column(name = "model_name", nullable = false) private String modelName = "";
    @Column(nullable = false) private Integer dimensions = 1024;
    @Column(name = "encrypted_api_key", columnDefinition = "TEXT") private String encryptedApiKey;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "validated_at") private LocalDateTime validatedAt;
    @Column(name = "configuration_version", nullable = false) private String configurationVersion = "unconfigured";
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt = LocalDateTime.now();
    @Version private long revision;
    @Override public String toString() { return "AdminEmbeddingSetting[redacted]"; }
}
