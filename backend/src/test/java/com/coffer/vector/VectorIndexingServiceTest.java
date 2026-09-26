package com.coffer.vector;

import com.coffer.config.EmbeddingProperties;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.application.parse.DocumentParseService;
import com.coffer.model.provider.EmbeddingProvider;
import com.coffer.service.MinioStorageService;
import com.coffer.service.RetryableModelService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorIndexingServiceTest {

    private EmbeddingProperties properties;
    private EmbeddingProvider embeddingProvider;
    private RedisVectorStore redisVectorStore;
    private RetryableModelService retryableModelService;
    private VectorIndexingService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new EmbeddingProperties();
        properties.setEnabled(true);
        properties.setModelName("text-embedding-v3");

        embeddingProvider = mock(EmbeddingProvider.class);
        ObjectProvider<EmbeddingProvider> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(embeddingProvider);
        redisVectorStore = mock(RedisVectorStore.class);
        retryableModelService = mock(RetryableModelService.class);
        when(retryableModelService.executeWithRetry(anyString(), any()))
                .thenAnswer(invocation -> ((Supplier<float[]>) invocation.getArgument(1)).get());

        service = new VectorIndexingService(properties, provider, redisVectorStore,
                retryableModelService, mock(MinioStorageService.class), mock(DocumentParseService.class));
    }

    @Test
    void successfulMultiChunkIndexUsesRetryServiceAndMarksMetadataIndexed() {
        FileMetadata metadata = metadata(42L);
        String text = "x".repeat(1500);
        when(embeddingProvider.embed(anyString()))
                .thenReturn(Response.from(Embedding.from(new float[]{0.1f, 0.2f, 0.3f})));

        boolean indexed = service.indexParsedText(metadata, text);

        assertThat(indexed).isTrue();
        assertThat(metadata.getVectorIndexedAt()).isNotNull();
        verify(retryableModelService, times(2)).executeWithRetry(anyString(), any());

        ArgumentCaptor<VectorRecord> records = ArgumentCaptor.forClass(VectorRecord.class);
        verify(redisVectorStore, times(2)).save(records.capture());
        assertThat(records.getAllValues())
                .extracting(VectorRecord::documentId)
                .containsExactly("42:0:0", "42:0:1");
        assertThat(records.getAllValues())
                .extracting(VectorRecord::fileId)
                .containsOnly(42L);
        assertThat(records.getAllValues())
                .extracting(VectorRecord::chunkIndex)
                .containsExactly(0, 1);
        assertThat(records.getAllValues().get(0).content()).hasSize(800);
        assertThat(records.getAllValues().get(1).content()).hasSize(800);
        verify(redisVectorStore).finishFileIndex(42L, java.util.List.of("42:0:0", "42:0:1"));
    }

    @Test
    void redisFailureLeavesMetadataPendingAndDoesNotEscape() {
        FileMetadata metadata = metadata(7L);
        when(embeddingProvider.embed(anyString()))
                .thenReturn(Response.from(Embedding.from(new float[]{0.4f, 0.5f})));
        doThrow(new IllegalStateException("Redis unavailable"))
                .when(redisVectorStore).save(any(VectorRecord.class));

        boolean indexed = service.indexParsedText(metadata, "content");

        assertThat(indexed).isFalse();
        assertThat(metadata.getVectorIndexedAt()).isNull();
        verify(retryableModelService).executeWithRetry(anyString(), any());
    }

    private FileMetadata metadata(long id) {
        FileMetadata metadata = FileMetadata.builder()
                .fileName("document.txt")
                .storagePath("files/document.txt")
                .build();
        metadata.setId(id);
        return metadata;
    }
}
