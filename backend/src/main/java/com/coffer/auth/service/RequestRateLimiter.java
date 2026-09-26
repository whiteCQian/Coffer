package com.coffer.auth.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Small per-process fixed-window limiter for credential and model-test endpoints. */
@Component
public class RequestRateLimiter {

    private static final int MAX_BUCKETS = 20_000;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong operations = new AtomicLong();

    public void requireAllowed(String key, int maximum, Duration duration) {
        long now = System.currentTimeMillis();
        long windowMillis = Math.max(1_000L, duration.toMillis());
        if (!windows.containsKey(key) && windows.size() >= MAX_BUCKETS) {
            windows.entrySet().stream()
                    .min(java.util.Comparator.comparingLong(entry -> entry.getValue().startedAt))
                    .ifPresent(oldest -> windows.remove(oldest.getKey(), oldest.getValue()));
        }
        Window bucket = windows.compute(key, (ignored, current) -> {
            if (current == null || now - current.startedAt >= windowMillis) {
                return new Window(now, 1);
            }
            return new Window(current.startedAt, current.count + 1);
        });
        if (operations.incrementAndGet() % 256 == 0) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt > windowMillis * 2);
        }
        if (bucket.count > maximum) {
            throw new AuthFailureException(HttpStatus.TOO_MANY_REQUESTS,
                    "请求过于频繁，请稍后重试");
        }
        if (windows.size() > MAX_BUCKETS) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt > windowMillis);
        }
    }

    private record Window(long startedAt, int count) { }
}
