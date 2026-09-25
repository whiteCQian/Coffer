package com.coffer.model.runtime.api;

import com.coffer.governance.domain.GovernanceRunMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/** Public runtime mode status without any credential material. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelRuntimeModeStatusResponse {
    private GovernanceRunMode activeMode;
    private boolean currentModeValidated;
    private Map<String, Boolean> validatedModes;
    private Map<String, LocalDateTime> validatedAt;
    private Boolean validationSuccess;
    private String message;
    private Map<String, String> capabilities;
}
