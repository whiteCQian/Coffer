package com.coffer.model.runtime;

import com.coffer.governance.domain.GovernanceRunMode;
import lombok.Data;

@Data
public class AdminEmbeddingRequest {
    private GovernanceRunMode mode;
    private String baseUrl;
    private String modelName;
    private Integer dimensions;
    private String apiKey;
    @Override public String toString() { return "AdminEmbeddingRequest[redacted]"; }
}
