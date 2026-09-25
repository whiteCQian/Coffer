package com.coffer.model.runtime.api;

import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.model.runtime.ModelRuntimeCapability;
import lombok.Builder;
import lombok.Value;

/** Safe view of one effective runtime endpoint; API keys are always masked. */
@Value
@Builder
public class ModelRuntimeEndpointResponse {

    GovernanceRunMode mode;
    ModelRuntimeCapability capability;
    String baseUrl;
    String modelName;
    boolean configured;
    String maskedApiKey;
    String credentialProvider;
    String source;
    boolean embeddingEnabled;
}
