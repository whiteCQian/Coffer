package com.coffer.vector;

import com.coffer.config.EmbeddingProperties;
import com.coffer.file.domain.FileMetadata;
import com.coffer.exception.EmbeddingRetryException;
import com.coffer.file.application.parse.DocumentParseService;
import com.coffer.service.MinioStorageService;
import com.coffer.service.RetryableModelService;
import com.coffer.model.provider.EmbeddingProvider;
import com.coffer.file.domain.parse.ParseResult;
import com.coffer.file.domain.parse.ParseStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Creates Redis Vector Set entries for parsed text without affecting file availability on failure. */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorIndexingService {

    private static final int CHUNK_SIZE = 800;
    private static final int CHUNK_OVERLAP = 100;

    private final EmbeddingProperties embeddingProperties;
    private final ObjectProvider<EmbeddingProvider> embeddingProvider;
    private final RedisVectorStore redisVectorStore;
    private final RetryableModelService retryableModelService;
    private final MinioStorageService minioStorageService;
    private final DocumentParseService documentParseService;

    /**
     * Indexes an already parsed document. All chunks must reach Redis before the
     * caller marks the MySQL record as indexed.
     */
    public boolean indexParsedText(FileMetadata metadata, String text) {
        return indexParsedText(metadata, text, null);
    }

    public boolean indexParsedText(FileMetadata metadata, String text, String generation) {
        if (!embeddingProperties.isEnabled()) {
            return false;
        }
        try {
            EmbeddingProvider model = embeddingProvider.getIfAvailable();
            if (model == null) {
                throw new IllegalStateException("Embedding 已启用，但 Embedding Provider 未初始化");
            }
            List<String> chunks = chunk(text);
            if (chunks.isEmpty()) {
                return false;
            }
            List<String> documentIds = new ArrayList<>(chunks.size());
            for (int index = 0; index < chunks.size(); index++) {
                String chunk = chunks.get(index);
                float[] vector = retryableModelService.executeWithRetry("Embedding 文件 " + metadata.getId(), () -> {
                    try {
                        return model.embed(chunk).content().vector();
                    } catch (RuntimeException e) {
                        throw new EmbeddingRetryException("Embedding 调用失败", e);
                    }
                });
                String documentId = metadata.getId() + ":" + index;
                VectorRecord record = new VectorRecord(documentId, metadata.getId(), index, chunk,
                        embeddingProperties.getModelName(), vector, null);
                if (generation == null) redisVectorStore.save(record);
                else redisVectorStore.save(record, generation);
                documentIds.add(documentId);
            }
            if (generation == null) redisVectorStore.finishFileIndex(metadata.getId(), documentIds);
            else redisVectorStore.finishFileIndex(metadata.getId(), documentIds, generation);
            metadata.setVectorIndexedAt(LocalDateTime.now());
            metadata.setVectorIndexGeneration(generation == null ? redisVectorStore.activeGeneration() : generation);
            log.info("文件向量索引完成 fileId={}, chunks={}", metadata.getId(), chunks.size());
            return true;
        } catch (Exception e) {
            log.warn("文件向量索引失败，将由后台补偿 fileId={}: {}", metadata.getId(), e.getMessage());
            return false;
        }
    }

    /** Re-parses the original object for a file whose prior Redis write failed. */
    public boolean reindex(FileMetadata metadata) {
        return reindex(metadata, null);
    }

    public boolean reindex(FileMetadata metadata, String generation) {
        try (InputStream input = minioStorageService.getFileStream(null, metadata.getStoragePath())) {
            ParseResult result = documentParseService.extractTextFromFile(metadata.getFileName(), input);
            if (result.getStatus() != ParseStatus.SUCCESS) {
                log.warn("向量补偿跳过无法解析的文件 fileId={}, status={}", metadata.getId(), result.getStatus());
                return false;
            }
            return indexParsedText(metadata, result.getContent(), generation);
        } catch (Exception e) {
            log.warn("向量补偿读取文件失败 fileId={}: {}", metadata.getId(), e.getMessage());
            return false;
        }
    }

    private List<String> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.trim();
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < normalized.length();) {
            int end = Math.min(start + CHUNK_SIZE, normalized.length());
            chunks.add(normalized.substring(start, end));
            if (end == normalized.length()) {
                break;
            }
            start = end - CHUNK_OVERLAP;
        }
        return chunks;
    }
}
