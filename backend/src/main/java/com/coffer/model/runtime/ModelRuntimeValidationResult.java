package com.coffer.model.runtime;

import java.util.Map;

/** Result of validating the model capabilities required by a runtime mode. */
public record ModelRuntimeValidationResult(
        boolean success,
        Map<String, String> capabilities,
        String message) {
}
