package com.coffer.operations;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.*;
import org.springframework.stereotype.Component;
@Component("runtimeReadiness") @RequiredArgsConstructor
public class RuntimeReadinessIndicator implements HealthIndicator {
    private final RuntimeMonitor monitor;
    public Health health() { return Health.status(monitor.snapshot().readiness()).build(); }
}
