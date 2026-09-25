package com.coffer.model.runtime;

import com.coffer.governance.domain.GovernanceRunMode;

/** Published whenever a runtime endpoint or its credential changes. */
public record ModelRuntimeConfigurationChangedEvent(
        GovernanceRunMode mode,
        ModelRuntimeCapability capability) {
}
