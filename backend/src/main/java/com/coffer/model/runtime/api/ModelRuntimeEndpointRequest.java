package com.coffer.model.runtime.api;

import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.model.runtime.ModelRuntimeCapability;
import lombok.Data;

/** Request used to save or test one runtime capability endpoint. */
@Data
public class ModelRuntimeEndpointRequest {

    private GovernanceRunMode mode;
    private ModelRuntimeCapability capability;
    private String baseUrl;
    private String modelName;
    private String apiKey;
}
