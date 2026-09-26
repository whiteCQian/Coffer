package com.coffer.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class TenantScheduledTaskAspect {

    private final TenantJobRunner runner;

    @Around("@annotation(com.coffer.auth.service.OwnerScheduled)")
    public Object runOncePerOwner(ProceedingJoinPoint invocation) {
        if (TenantContext.currentTenantId() > 0L) {
            return proceed(invocation);
        }
        runner.runForEnabledOwners(ownerId -> {
            try {
                TenantContext.runAs(ownerId, () -> {
                    try {
                        invocation.proceed();
                    } catch (Throwable failure) {
                        throw new ScheduledJobFailure(failure);
                    }
                });
            } catch (Throwable failure) {
                log.warn("按账号执行后台任务失败 ownerId={} exceptionType={}",
                        ownerId, (failure.getCause() == null ? failure : failure.getCause()).getClass().getSimpleName());
            }
        });
        return null;
    }

    private Object proceed(ProceedingJoinPoint invocation) {
        try {
            return invocation.proceed();
        } catch (Throwable failure) {
            throw new ScheduledJobFailure(failure);
        }
    }

    private static final class ScheduledJobFailure extends RuntimeException {
        private ScheduledJobFailure(Throwable cause) {
            super(cause);
        }
    }
}
