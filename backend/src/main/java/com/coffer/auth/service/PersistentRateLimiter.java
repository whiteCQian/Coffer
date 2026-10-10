package com.coffer.auth.service;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Duration;
import com.coffer.web.WebLimits;

/** Production credential attempts survive process restarts; hashed keys contain no stored usernames/IPs. */
@Service @Profile("prod") @RequiredArgsConstructor
public class PersistentRateLimiter {
    private final JdbcTemplate jdbc;
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean acquire(String key, int maximum, Duration duration) {
        long now = System.currentTimeMillis();
        String hash = WebLimits.hash(key);
        jdbc.queryForObject("SELECT id FROM auth_rate_lock WHERE id=1 FOR UPDATE", Long.class);
        jdbc.update("DELETE FROM auth_rate_bucket WHERE expires_at<=?", now);
        var previous = jdbc.queryForList("SELECT count FROM auth_rate_bucket WHERE key_hash=?", hash);
        if (previous.isEmpty()) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM auth_rate_bucket", Long.class) >= 20000) return false;
            jdbc.update("INSERT INTO auth_rate_bucket(key_hash,count,expires_at) VALUES(?,1,?)", hash, now + Math.max(1000, duration.toMillis()));
            return true;
        }
        long count = ((Number)previous.get(0).get("count")).longValue();
        if (count >= maximum) return false;
        jdbc.update("UPDATE auth_rate_bucket SET count=count+1 WHERE key_hash=?", hash);
        return true;
    }
}
