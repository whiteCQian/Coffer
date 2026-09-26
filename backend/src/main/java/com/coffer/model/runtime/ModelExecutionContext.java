package com.coffer.model.runtime;

import com.coffer.auth.service.TenantContext;
import com.coffer.governance.domain.GovernanceRunMode;
import java.util.Map;
import java.util.function.Supplier;

/** Captured values, never late-bound settings. Secret-bearing objects have a redacted toString. */
public final class ModelExecutionContext {
    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();
    private ModelExecutionContext() {}
    public record Snapshot(String id, Long ownerId, String version, GovernanceRunMode mode,
                           Map<ModelRuntimeCapability, ResolvedModelRuntimeEndpoint> endpoints) {
        public Snapshot { endpoints = Map.copyOf(endpoints); }
        @Override public String toString() { return "ModelExecutionSnapshot[redacted]"; }
    }
    public static Snapshot current() {
        Snapshot snapshot = CURRENT.get();
        if (snapshot != null && !snapshot.ownerId().equals(TenantContext.requireOwnerId()))
            throw new org.springframework.security.access.AccessDeniedException("无权执行此操作");
        return snapshot;
    }
    public static Snapshot require() {
        Snapshot snapshot = current();
        if (snapshot == null) throw new ModelConsentRequiredException();
        return snapshot;
    }
    public static String currentId() { Snapshot s = current(); return s == null ? null : s.id(); }
    public static <T> T with(Snapshot snapshot, Supplier<T> action) {
        Snapshot previous = CURRENT.get();
        if (snapshot == null) CURRENT.remove(); else CURRENT.set(snapshot);
        try { current(); return action.get(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
    public static void with(Snapshot snapshot, Runnable action) { with(snapshot, () -> { action.run(); return null; }); }
}
