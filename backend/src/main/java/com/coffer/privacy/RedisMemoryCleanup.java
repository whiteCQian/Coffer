package com.coffer.privacy;

import com.coffer.auth.service.TenantContext;
import com.coffer.memory.RedisChatMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component @Profile("!desktop") @RequiredArgsConstructor @com.coffer.auth.service.OwnerOnly
public class RedisMemoryCleanup implements MemoryCleanup {
    private final StringRedisTemplate redis;
    public void delete(String sessionId) {
        java.util.UUID.fromString(sessionId);
        redis.delete(RedisChatMemoryStore.KEY_PREFIX + TenantContext.requireOwnerId() + ":" + sessionId);
    }
}
