package com.coffer.auth.service;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect @Component @Order(-200) @RequiredArgsConstructor
public class OwnedJobAspect {
    private final OwnerAuthorization authorization;

    @Around("@annotation(com.coffer.auth.service.OwnedJob) && args(work)")
    public Object execute(ProceedingJoinPoint invocation, OwnedWork work) throws Throwable {
        Long previous = TenantContext.currentTenantId();
        if (previous > 0 && !previous.equals(work.ownerId())) throw new ResourceNotFoundException();
        try {
            TenantContext.set(work.ownerId());
            authorization.requireOwner();
            return invocation.proceed();
        } finally {
            TenantContext.set(previous);
        }
    }
}
