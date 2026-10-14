package com.coffer.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

@Component @org.springframework.context.annotation.Profile("!desktop") @RequiredArgsConstructor
public class AccountCachePurger {
    private final StringRedisTemplate redis;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Value("${coffer.vector-store.redis.key-prefix:coffer:vector:}") private String vectorPrefix;
    public void purge(long owner) {
        if(owner<=0 || jdbc.queryForObject("SELECT COUNT(*) FROM app_user u JOIN account_deletion_job j ON j.target_user_id=u.id WHERE u.id=? AND u.enabled=FALSE AND u.deletion_status='RUNNING' AND j.status='RUNNING'",Long.class,owner)!=1)
            throw new org.springframework.security.access.AccessDeniedException("账号删除未授权");
        removePrefix(vectorPrefix + "owner:" + owner + ":");
        removePrefix("chat:memory:v2:" + owner + ":");
    }
    private void removePrefix(String prefix) {
        try(var cursor=redis.scan(ScanOptions.scanOptions().match(prefix + "*").count(200).build())) {
            var keys=new java.util.ArrayList<String>(200);
            while(cursor.hasNext()) {
                String key=cursor.next();
                if(!key.startsWith(prefix))throw new IllegalStateException("缓存清理范围不匹配");
                keys.add(key); if(keys.size()==200) { redis.delete(keys); keys.clear(); }
            }
            if(!keys.isEmpty())redis.delete(keys);
        }
    }
}
