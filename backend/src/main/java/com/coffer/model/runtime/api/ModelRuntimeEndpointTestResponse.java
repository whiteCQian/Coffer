package com.coffer.model.runtime.api;

import com.coffer.model.runtime.ModelRuntimeCapability;
import lombok.Builder;
import lombok.Value;

/** Result of testing one explicit runtime endpoint. */
@Value
@Builder
public class ModelRuntimeEndpointTestResponse {

    boolean success;
    ModelRuntimeCapability capability;
    String message;
}
