package com.coffer.config;

import com.coffer.auth.service.TenantContext;
import org.springframework.core.task.TaskDecorator;

public class TenantContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Long ownerId = TenantContext.currentTenantId();
        var snapshot = com.coffer.model.runtime.ModelExecutionContext.current();
        return () -> TenantContext.runAs(ownerId,
                () -> com.coffer.model.runtime.ModelExecutionContext.with(snapshot, runnable));
    }
}
