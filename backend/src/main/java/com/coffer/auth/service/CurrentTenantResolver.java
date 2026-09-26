package com.coffer.auth.service;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

@Component
public class CurrentTenantResolver implements CurrentTenantIdentifierResolver<Long> {

    @Override
    public Long resolveCurrentTenantIdentifier() {
        return TenantContext.currentTenantId();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
