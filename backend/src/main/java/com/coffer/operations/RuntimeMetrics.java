package com.coffer.operations;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
@Component
public class RuntimeMetrics {
    public RuntimeMetrics(RuntimeMonitor monitor, MeterRegistry registry) {
        registry.gauge("coffer.runtime.ready", monitor, m -> "UP".equals(m.snapshot().readiness()) ? 1 : 0);
        for (String state : java.util.List.of("active", "idle", "waiting", "limit"))
            registry.gauge("coffer.runtime.database.connections", java.util.List.of(io.micrometer.core.instrument.Tag.of("state", state)), monitor,
                    m -> m.snapshot().connections().containsKey(state) ? m.snapshot().connections().get(state) : Double.NaN);
        for (String counter : java.util.List.of("pendingTasks", "processingTasks", "failedTasks", "manualReview", "pendingCompensations", "pendingDeletions", "pendingWrites", "pendingRenames", "pendingWorkSaves", "pendingVectorCleanups", "failedVectorReindexes"))
            registry.gauge("coffer.runtime.queue", java.util.List.of(io.micrometer.core.instrument.Tag.of("kind", counter)), monitor,
                    m -> m.snapshot().queues().containsKey(counter) ? m.snapshot().queues().get(counter) : Double.NaN);
    }
}
