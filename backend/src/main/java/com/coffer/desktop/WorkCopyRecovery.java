package com.coffer.desktop;

import com.coffer.auth.service.TenantJobRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

@Component @Profile("desktop") @RequiredArgsConstructor
public class WorkCopyRecovery {
    private final TenantJobRunner owners;
    private final WorkCopyService copies;
    @EventListener(ApplicationReadyEvent.class) @org.springframework.core.annotation.Order(20)
    public void onStartup() { owners.runForEnabledOwners(owner -> copies.recover()); }
}
