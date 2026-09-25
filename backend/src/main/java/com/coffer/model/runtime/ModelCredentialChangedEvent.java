package com.coffer.model.runtime;

import com.coffer.entity.ModelProvider;

/** Published when a credential changes so previous connectivity validation is no longer trusted. */
public record ModelCredentialChangedEvent(ModelProvider provider) {
}
