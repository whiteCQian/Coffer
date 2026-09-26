package com.coffer.vector;

import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.BooleanOutput;
import io.lettuce.core.output.IntegerOutput;
import com.coffer.auth.service.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.DecoratedRedisConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the minimum Redis vector-store round trip against the local Redis
 * instance used by the existing Redis integration tests.
 */
@SpringBootTest(properties = {
        "minio.access-key=test-access-key",
        "minio.secret-key=test-secret-key"
})
class RedisVectorStoreTest extends com.coffer.auth.OwnerTestSupport {

    private static final String DOCUMENT_ID = "vector-store-test";

    private String vectorPrefix() { return "coffer:vector:owner:" + TenantContext.requireOwnerId() + ":"; }

    @Autowired
    private RedisVectorStore store;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void cleanup() {
        redisTemplate.delete(vectorPrefix() + "metadata:" + DOCUMENT_ID);
        redisTemplate.execute((RedisCallback<Boolean>) connection -> {
            LettuceConnection lettuceConnection = unwrap(connection);
            return (Boolean) lettuceConnection.execute("VREM", new BooleanOutput<>(ByteArrayCodec.INSTANCE),
                    (vectorPrefix() + "index").getBytes(StandardCharsets.UTF_8),
                    DOCUMENT_ID.getBytes(StandardCharsets.UTF_8));
        });
    }

    @Test
    void savesAndReadsVectorRecord() {
        float[] vector = {0.25f, -0.5f, 0.75f};
        store.save(new VectorRecord(DOCUMENT_ID, "text-embedding-v3", vector, null));

        Optional<VectorRecord> restored = store.find(DOCUMENT_ID);

        assertThat(restored).isPresent();
        assertThat(restored.orElseThrow().documentId()).isEqualTo(DOCUMENT_ID);
        assertThat(restored.orElseThrow().embeddingModel()).isEqualTo("text-embedding-v3");
        assertThat(restored.orElseThrow().vector()).containsExactly(vector);
        assertThat(restored.orElseThrow().updatedAt()).isNotNull();

        Long dimension = redisTemplate.execute((RedisCallback<Long>) connection -> {
            LettuceConnection lettuceConnection = unwrap(connection);
            return (Long) lettuceConnection.execute("VDIM", new IntegerOutput<>(ByteArrayCodec.INSTANCE),
                    (vectorPrefix() + "index").getBytes(StandardCharsets.UTF_8));
        });
        assertThat(dimension).isEqualTo(3L);
    }

    @Test
    void missingVectorReturnsEmpty() {
        assertThat(store.find("missing-vector-store-test")).isEmpty();
    }

    @Test
    void searchesVectorSetAndReturnsSimilarityOrderedHits() {
        String first = "91:0";
        String second = "92:0";
        store.save(new VectorRecord(first, "text-embedding-v3", new float[]{1.0f, 0.0f, 0.0f}, null));
        store.save(new VectorRecord(second, "text-embedding-v3", new float[]{0.0f, 1.0f, 0.0f}, null));

        var results = store.searchSimilar(new float[]{0.95f, 0.05f, 0.0f}, 2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).documentId()).isEqualTo(first);
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
        deleteVector(first);
        deleteVector(second);
    }

    private void deleteVector(String documentId) {
        redisTemplate.delete(vectorPrefix() + "metadata:" + documentId);
        redisTemplate.execute((RedisCallback<Boolean>) connection -> {
            LettuceConnection lettuceConnection = unwrap(connection);
            return (Boolean) lettuceConnection.execute("VREM", new BooleanOutput<>(ByteArrayCodec.INSTANCE),
                    (vectorPrefix() + "index").getBytes(StandardCharsets.UTF_8),
                    documentId.getBytes(StandardCharsets.UTF_8));
        });
    }

    private LettuceConnection unwrap(RedisConnection connection) {
        RedisConnection target = connection;
        while (target instanceof DecoratedRedisConnection decoratedConnection) {
            target = decoratedConnection.getDelegate();
        }
        return (LettuceConnection) target;
    }
}
