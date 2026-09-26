package com.coffer.vector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.coffer.auth.service.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.BooleanOutput;
import io.lettuce.core.output.IntegerOutput;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.output.ScoredValueListOutput;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.DecoratedRedisConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Minimal Redis 8 vector-set backed vector record store.
 *
 * <p>The vector itself is stored in a Redis Vector Set through the native
 * {@code VADD}/{@code VREM} commands. Metadata is stored separately as JSON
 * so the next hybrid-search step can attach file and embedding information
 * without coupling it to the vector protocol.</p>
 */
@Slf4j
@Component
@com.coffer.auth.service.OwnerOnly
@RequiredArgsConstructor
public class RedisVectorStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${coffer.vector-store.redis.key-prefix:coffer:vector:}")
    private String keyPrefix;

    public void save(VectorRecord record) {
        save(record, activeGeneration());
    }

    public void save(VectorRecord record, String generation) {
        try {
            redisTemplate.execute((RedisConnection connection) ->
                    executeBoolean(connection, "VADD", vectorAddArguments(record, generation)));
            redisTemplate.opsForValue().set(metadataKey(record.documentId(), generation),
                    objectMapper.writeValueAsString(record));
            if (generation != null) {
                redisTemplate.opsForSet().add(generationDocumentsKey(generation), record.documentId());
            }
            if (record.fileId() != null) {
                redisTemplate.opsForSet().add(fileChunksKey(record.fileId(), generation), record.documentId());
            }
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("向量记录序列化失败: " + record.documentId(), e);
        }
    }

    public Optional<VectorRecord> find(String documentId) {
        String json = redisTemplate.opsForValue().get(metadataKey(documentId, activeGeneration()));
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, VectorRecord.class));
        } catch (JsonProcessingException e) {
            log.warn("Redis 向量记录反序列化失败，异常类型={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Searches the Redis 8 Vector Set and returns hits in similarity order. */
    @SuppressWarnings("unchecked")
    public List<VectorSearchResult> searchSimilar(float[] queryVector, int maxResults) {
        return searchSimilar(queryVector, maxResults, activeGeneration());
    }

    public List<VectorSearchResult> searchSimilar(float[] queryVector, int maxResults, String generation) {
        if (queryVector == null || queryVector.length == 0 || maxResults <= 0) {
            return List.of();
        }
        List<byte[]> arguments = new ArrayList<>();
        arguments.add(bytes(indexKey(generation)));
        arguments.add(bytes("VALUES"));
        arguments.add(bytes(Integer.toString(queryVector.length)));
        for (float value : queryVector) {
            arguments.add(bytes(Float.toString(value)));
        }
        arguments.add(bytes("COUNT"));
        arguments.add(bytes(Integer.toString(maxResults)));
        arguments.add(bytes("WITHSCORES"));

        List<ScoredValue<byte[]>> raw = redisTemplate.execute((RedisConnection connection) -> {
            LettuceConnection lettuceConnection = requireLettuce(connection);
            return (List<ScoredValue<byte[]>>) (List<?>) lettuceConnection.execute(
                    "VSIM", new ScoredValueListOutput<>(ByteArrayCodec.INSTANCE),
                    arguments.toArray(byte[][]::new));
        });
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream()
                .filter(value -> value != null && value.hasValue())
                .map(value -> new VectorSearchResult(
                        new String(value.getValue(), StandardCharsets.UTF_8), value.getScore()))
                .toList();
    }

    public void delete(String documentId) {
        delete(documentId, activeGeneration());
    }

    public void delete(String documentId, String generation) {
        redisTemplate.execute((RedisConnection connection) -> executeBoolean(
                connection, "VREM", bytes(indexKey(generation)), bytes(documentId)));
        redisTemplate.delete(metadataKey(documentId, generation));
    }

    /** Idempotently removes every chunk belonging to a file. */
    public void deleteFile(Long fileId) {
        deleteFile(fileId, activeGeneration());
    }

    public void deleteFile(Long fileId, String generation) {
        Set<String> ids = redisTemplate.opsForSet().members(fileChunksKey(fileId, generation));
        if (ids != null) ids.forEach(id -> delete(id, generation));
        redisTemplate.delete(fileChunksKey(fileId, generation));
    }

    /** Removes stale chunks left by an older version of the same file index. */
    public void finishFileIndex(Long fileId, Collection<String> currentDocumentIds) {
        finishFileIndex(fileId, currentDocumentIds, activeGeneration());
    }

    public void finishFileIndex(Long fileId, Collection<String> currentDocumentIds, String generation) {
        String chunksKey = fileChunksKey(fileId, generation);
        Set<String> previous = redisTemplate.opsForSet().members(chunksKey);
        Set<String> current = new HashSet<>(currentDocumentIds);
        if (previous != null) {
            previous.stream().filter(id -> !current.contains(id)).forEach(id -> delete(id, generation));
        }
        redisTemplate.delete(chunksKey);
        if (!current.isEmpty()) {
            redisTemplate.opsForSet().add(chunksKey, current.toArray(String[]::new));
        }
    }

    public String activeGeneration() {
        String generation = redisTemplate.opsForValue().get(ownerPrefix() + "active-generation");
        return generation == null || generation.isBlank() ? null : generation;
    }

    public void setActiveGeneration(String generation) {
        generationPrefix(generation);
        if (generation == null) throw new IllegalArgumentException("generation 不能为空");
        redisTemplate.opsForValue().set(ownerPrefix() + "active-generation", generation);
    }

    public void deleteGeneration(String generation) {
        Set<String> documents = redisTemplate.opsForSet().members(generationDocumentsKey(generation));
        if (documents != null) documents.forEach(id -> {
            delete(id, generation);
            String first = id.split(":")[0];
            if (first.matches("[1-9][0-9]*")) redisTemplate.delete(fileChunksKey(Long.valueOf(first), generation));
        });
        redisTemplate.delete(indexKey(generation));
        redisTemplate.delete(generationDocumentsKey(generation));
    }

    public boolean hasGeneration(String generation) {
        Set<String> documents = redisTemplate.opsForSet().members(generationDocumentsKey(generation));
        return documents != null && !documents.isEmpty();
    }

    public Long dimension(String generation) {
        return redisTemplate.execute((RedisConnection connection) ->
                (Long) requireLettuce(connection).execute("VDIM", new IntegerOutput<>(ByteArrayCodec.INSTANCE),
                        bytes(indexKey(generation))));
    }

    /**
     * Redis 8 Vector Set commands return a RESP boolean. Spring Data's generic
     * execute method assumes a byte-array reply, so use Lettuce's BooleanOutput
     * explicitly for these commands.
     */
    private Boolean executeBoolean(RedisConnection connection, String command, byte[]... arguments) {
        return (Boolean) requireLettuce(connection).execute(
                command, new BooleanOutput<>(ByteArrayCodec.INSTANCE), arguments);
    }

    private LettuceConnection requireLettuce(RedisConnection connection) {
        RedisConnection target = connection;
        while (target instanceof DecoratedRedisConnection decoratedConnection) {
            target = decoratedConnection.getDelegate();
        }
        if (!(target instanceof LettuceConnection lettuceConnection)) {
            throw new IllegalStateException("Redis 向量存储需要 Lettuce 客户端");
        }
        return lettuceConnection;
    }

    private String metadataKey(String documentId) { return metadataKey(documentId, null); }
    private String metadataKey(String documentId, String generation) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        return generationPrefix(generation) + "metadata:" + documentId;
    }

    private String indexKey() { return indexKey(null); }
    private String indexKey(String generation) {
        return generationPrefix(generation) + "index";
    }

    private String fileChunksKey(Long fileId) { return fileChunksKey(fileId, null); }
    private String fileChunksKey(Long fileId, String generation) {
        return generationPrefix(generation) + "file:" + fileId + ":chunks";
    }

    private String generationDocumentsKey(String generation) { return generationPrefix(generation) + "documents"; }
    private String generationPrefix(String generation) {
        if (generation != null && !generation.matches("[A-Za-z0-9_-]{1,100}"))
            throw new IllegalArgumentException("generation 格式非法");
        return generation == null || generation.isBlank()
                ? ownerPrefix() : ownerPrefix() + "generation:" + generation + ":";
    }

    private String ownerPrefix() {
        return keyPrefix + "owner:" + TenantContext.requireOwnerId() + ":";
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private byte[][] vectorAddArguments(VectorRecord record, String generation) {
        List<byte[]> arguments = new ArrayList<>();
        arguments.add(bytes(indexKey(generation)));
        arguments.add(bytes("VALUES"));
        arguments.add(bytes(Integer.toString(record.vector().length)));
        for (float value : record.vector()) {
            arguments.add(bytes(Float.toString(value)));
        }
        arguments.add(bytes(record.documentId()));
        return arguments.toArray(byte[][]::new);
    }
}
