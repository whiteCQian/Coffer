package com.coffer.vector;

import com.coffer.auth.service.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.UUID;

/** Standalone deployment probe; all keys live in a unique temporary namespace. */
public class AdminVectorRedisProbe {
    public static void main(String[] args) throws Exception {
        String prefix = "coffer:admin-vector-probe:" + UUID.randomUUID() + ":";
        var connection = new LettuceConnectionFactory(args[0], Integer.parseInt(args[1]));
        connection.afterPropertiesSet(); connection.start();
        var redis = new StringRedisTemplate(connection); redis.afterPropertiesSet();
        try {
            var store = new RedisVectorStore(redis, new ObjectMapper().findAndRegisterModules());
            var field = RedisVectorStore.class.getDeclaredField("keyPrefix"); field.setAccessible(true); field.set(store,prefix);
            String a = "a".repeat(64), b = "b".repeat(64);
            TenantContext.set(1L);
            String initial=store.prepareEmbeddingIndex(a);
            store.save(new VectorRecord("1:1:0","synthetic-vector",new float[]{0.2f,0.8f},java.time.Instant.now()));
            if (!store.hasGeneration(initial)) throw new AssertionError("Initial index missing");
            store.setActiveGeneration("synthetic-rebuild");
            if (!"synthetic-rebuild".equals(store.prepareEmbeddingIndex(a))) throw new AssertionError("Compatible config reset a rebuilt index");
            redis.delete(prefix + "owner:1:active-generation");
            if (!initial.equals(store.prepareEmbeddingIndex(a))) throw new AssertionError("Evicted pointer fell back to incompatible legacy vectors");
            String changed=store.prepareEmbeddingIndex(b);
            if (initial.equals(changed) || store.hasGeneration(changed)) throw new AssertionError("Incompatible index was reused");
            store.save(new VectorRecord("1:2:0","new-synthetic-vector",new float[]{0.2f,0.8f,0.1f},java.time.Instant.now()));
            if (store.dimension(changed) != 3L) throw new AssertionError("New dimension not isolated");
            TenantContext.set(2L);
            String other=store.prepareEmbeddingIndex(a);
            if (store.hasGeneration(other)) throw new AssertionError("Owner namespaces mixed");
            store.save(new VectorRecord("2:1:0","synthetic-vector",new float[]{0.2f,0.8f},java.time.Instant.now()));
            var hits=store.searchSimilar(new float[]{0.2f,0.8f},10);
            if (hits.size()!=1 || !hits.get(0).documentId().equals("2:1:0")) throw new AssertionError("Cross-owner search hit");
            System.out.println("PASS: Redis atomic model-version isolation, compatible rebuild preservation, dimensions and owner isolation");
        } finally {
            var keys=redis.keys(prefix + "*"); if(keys!=null&&!keys.isEmpty())redis.delete(keys);
            TenantContext.clear(); connection.destroy();
        }
    }
}
