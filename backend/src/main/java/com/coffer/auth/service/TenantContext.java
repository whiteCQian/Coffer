package com.coffer.auth.service;

import java.util.concurrent.Callable;

/** Request and background-job owner context consumed by Hibernate. */
public final class TenantContext {

    private static final Long ANONYMOUS_TENANT = 0L;
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() { }

    public static Long currentTenantId() {
        Long tenant = CURRENT.get();
        return tenant == null ? ANONYMOUS_TENANT : tenant;
    }

    public static Long requireOwnerId() {
        Long owner = CURRENT.get();
        if (owner == null || owner <= 0L) {
            throw new org.springframework.security.access.AccessDeniedException("无权执行此操作");
        }
        return owner;
    }

    public static void set(Long ownerId) {
        if (ownerId == null || ownerId <= 0L) CURRENT.remove();
        else CURRENT.set(ownerId);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static void runAs(Long ownerId, Runnable action) {
        Long previous = CURRENT.get();
        set(ownerId);
        try {
            action.run();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static <T> T callAs(Long ownerId, Callable<T> action) throws Exception {
        Long previous = CURRENT.get();
        set(ownerId);
        try {
            return action.call();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static <T> T supplyAs(Long ownerId, java.util.function.Supplier<T> action) {
        Long previous = CURRENT.get();
        set(ownerId);
        try { return action.get(); }
        finally { set(previous); }
    }
}
