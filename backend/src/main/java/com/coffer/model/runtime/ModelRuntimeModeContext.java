package com.coffer.model.runtime;

import com.coffer.governance.domain.GovernanceRunMode;

import java.util.function.Supplier;

/** Thread-bound mode snapshot used so a running task is isolated from later mode changes. */
public final class ModelRuntimeModeContext {

    private static final ThreadLocal<GovernanceRunMode> SNAPSHOT = new ThreadLocal<>();

    private ModelRuntimeModeContext() {
    }

    public static GovernanceRunMode current() {
        return SNAPSHOT.get();
    }

    public static <T> T withSnapshot(GovernanceRunMode mode, Supplier<T> action) {
        GovernanceRunMode previous = SNAPSHOT.get();
        SNAPSHOT.set(mode);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                SNAPSHOT.remove();
            } else {
                SNAPSHOT.set(previous);
            }
        }
    }

    public static void withSnapshot(GovernanceRunMode mode, Runnable action) {
        withSnapshot(mode, () -> {
            action.run();
            return null;
        });
    }
}
