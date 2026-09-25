package com.coffer.model.runtime.api;

import com.coffer.governance.domain.GovernanceRunMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Request selecting the target mode for validation or activation. */
@Data
public class ModelRuntimeModeRequest {
    @NotNull
    private GovernanceRunMode mode;
}
