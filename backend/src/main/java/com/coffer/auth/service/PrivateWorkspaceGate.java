package com.coffer.auth.service;

import java.util.concurrent.ConcurrentHashMap;

/** A non-waiting erase gate: active work finishes first; requests never deadlock with parallel searches. */
public final class PrivateWorkspaceGate {
    private static final ConcurrentHashMap<Long, State> STATES = new ConcurrentHashMap<>();
    private static final ThreadLocal<Long> ERASER = new ThreadLocal<>();
    private static final class State { int active; boolean erasing; }
    public interface Lease extends AutoCloseable { void close(); }
    private PrivateWorkspaceGate() {}
    public static Lease enter(Long owner) {
        if (owner.equals(ERASER.get())) return () -> {};
        State state = STATES.computeIfAbsent(owner, ignored -> new State());
        synchronized (state) {
            if (state.erasing) throw busy();
            state.active++;
        }
        return () -> { synchronized (state) { state.active--; } };
    }
    public static Lease erase(Long owner) {
        State state = STATES.computeIfAbsent(owner, ignored -> new State());
        synchronized (state) {
            if (state.erasing || state.active > 0) throw busy();
            state.erasing = true;
        }
        ERASER.set(owner);
        return () -> { ERASER.remove(); synchronized (state) { state.erasing = false; } };
    }
    private static AuthFailureException busy() {
        return new AuthFailureException(org.springframework.http.HttpStatus.CONFLICT, "工作区仍有操作进行中，请稍后重试删除");
    }
}
