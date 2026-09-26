package com.coffer.auth.service;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

@Aspect @Component @Order(-200)
public class PrivateRequestGateAspect {
    @Around("@within(org.springframework.web.bind.annotation.RestController) && execution(public * *(..)) && !execution(* com.coffer.privacy.PrivacyController.erase(..))")
    public Object request(ProceedingJoinPoint call) throws Throwable {
        Long owner = TenantContext.currentTenantId();
        if (owner == null || owner <= 0) return call.proceed();
        try (var lease = PrivateWorkspaceGate.enter(owner)) { return call.proceed(); }
    }
}
