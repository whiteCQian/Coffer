package com.coffer.auth.service;

import com.coffer.auth.infrastructure.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class TenantJobRunner {

    private final AppUserRepository users;
    @org.springframework.beans.factory.annotation.Value("${coffer.recovery.hold:false}")
    private boolean recoveryHold;

    public void runForEnabledOwners(java.util.function.LongConsumer action) {
        if(recoveryHold)return;
        for (Long ownerId : users.findEnabledOwnerIds()) {
            try {
                TenantContext.runAs(ownerId, () -> action.accept(ownerId));
            } catch (RuntimeException failure) {
                log.warn("后台账号任务失败，异常类型={}", failure.getClass().getSimpleName());
            }
        }
    }
}
