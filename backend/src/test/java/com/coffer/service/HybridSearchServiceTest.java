package com.coffer.service;

import com.coffer.config.HybridSearchProperties;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.model.provider.EmbeddingProvider;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.vector.RedisVectorStore;
import com.coffer.vector.VectorSearchResult;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class HybridSearchServiceTest {

    private FileMetadataRepository fileRepository;
    private FileTagMappingRepository tagMappingRepository;
    private RedisVectorStore vectorStore;
    private EmbeddingProvider embeddingProvider;
    private HybridSearchProperties properties;
    private HybridSearchService service;
    private Map<Long, FileMetadata> files;

    @BeforeEach
    void setUp() {
        fileRepository = mock(FileMetadataRepository.class);
        tagMappingRepository = mock(FileTagMappingRepository.class);
        vectorStore = mock(RedisVectorStore.class);
        embeddingProvider = mock(EmbeddingProvider.class);
        ObjectProvider<EmbeddingProvider> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(embeddingProvider);

        properties = new HybridSearchProperties();
        properties.setTopK(10);
        properties.setRrfK(60);
        service = new HybridSearchService(properties, fileRepository, tagMappingRepository,
                vectorStore, provider);

        FileMetadata first = metadata(1L, "精确命中.txt");
        FileMetadata second = metadata(2L, "语义命中.txt");
        FileMetadata third = metadata(3L, "双路命中.txt");
        files = Map.of(1L, first, 2L, second, 3L, third);
        when(fileRepository.findAllById(any())).thenAnswer(invocation ->
                StreamSupport.stream(((Iterable<Long>) invocation.getArgument(0)).spliterator(), false)
                        .map(files::get)
                        .filter(java.util.Objects::nonNull)
                        .toList());
        when(tagMappingRepository.findFileIdsByTagNameAndStatus(anyString(), any()))
                .thenReturn(List.of());
        when(embeddingProvider.embed(anyString()))
                .thenReturn(Response.from(Embedding.from(new float[]{0.1f, 0.2f})));
    }

    @Test
    void fusesLexicalAndVectorRanksWithRrf() {
        when(fileRepository.fullTextSearch("合同"))
                .thenReturn(List.of(files.get(1L), files.get(3L)));
        when(vectorStore.searchSimilar(any(), anyInt()))
                .thenReturn(List.of(new VectorSearchResult("3:0", 0.99),
                        new VectorSearchResult("2:0", 0.88)));

        List<HybridSearchService.SearchEvidence> evidence = service.searchWithEvidence("合同");

        assertThat(evidence).extracting(hit -> hit.file().getId())
                .containsExactly(3L, 1L, 2L);
        assertThat(evidence.get(0).retrievalType()).isEqualTo("HYBRID");
        assertThat(evidence.get(0).score()).isPositive();
    }

    @Test
    void vectorFailureKeepsLexicalResults() {
        when(fileRepository.fullTextSearch("关键词"))
                .thenReturn(List.of(files.get(1L)));
        when(vectorStore.searchSimilar(any(), anyInt()))
                .thenThrow(new IllegalStateException("Redis down"));

        List<FileMetadata> result = service.search("关键词");

        assertThat(result).extracting(FileMetadata::getId).containsExactly(1L);
    }

    @Test
    void fullTextFailureFallsBackToLikeResults() {
        when(fileRepository.fullTextSearch("图片"))
                .thenThrow(new IllegalStateException("H2 does not support MATCH"));
        when(fileRepository.findByFileNameOrSummaryContainingIgnoreCase("图片"))
                .thenReturn(List.of(files.get(2L)));
        when(vectorStore.searchSimilar(any(), anyInt())).thenReturn(List.of());

        List<FileMetadata> result = service.search("图片");

        assertThat(result).extracting(FileMetadata::getId).containsExactly(2L);
    }

    private FileMetadata metadata(long id, String name) {
        FileMetadata metadata = FileMetadata.builder().fileName(name).build();
        metadata.setId(id);
        return metadata;
    }
}
