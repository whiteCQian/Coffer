package com.coffer.model.runtime;

import com.coffer.entity.ModelProvider;
import com.coffer.governance.domain.GovernanceRunMode;

/** Effective endpoint after applying database overrides and environment defaults. */
public record ResolvedModelRuntimeEndpoint(
        GovernanceRunMode mode,
        ModelRuntimeCapability capability,
        String baseUrl,
        String modelName,
        String apiKey,
        String source,
        boolean customConfigured,
        ModelProvider credentialProvider) {
}
