package com.coffer.task;

import com.coffer.config.EmbeddingProperties;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.vector.VectorIndexingService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorReindexTaskTest {

    @Test
    void savesOnlyFilesWhoseReindexSucceeded() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setEnabled(true);
        FileMetadataRepository repository = mock(FileMetadataRepository.class);
        VectorIndexingService indexingService = mock(VectorIndexingService.class);
        FileMetadata successful = metadata(1L);
        FileMetadata failed = metadata(2L);
        when(repository.findByStatusAndVectorIndexedAtIsNull(eq(FileStatus.COMPLETED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(successful, failed)));
        when(indexingService.reindex(successful)).thenReturn(true);
        when(indexingService.reindex(failed)).thenReturn(false);

        new VectorReindexTask(properties, repository, indexingService).reindexPendingFiles();

        verify(repository).save(successful);
        verify(repository, never()).save(failed);
    }

    @Test
    void disabledFeatureDoesNotQueryPendingFiles() {
        EmbeddingProperties properties = new EmbeddingProperties();
        FileMetadataRepository repository = mock(FileMetadataRepository.class);
        VectorIndexingService indexingService = mock(VectorIndexingService.class);

        new VectorReindexTask(properties, repository, indexingService).reindexPendingFiles();

        verify(repository, never()).findByStatusAndVectorIndexedAtIsNull(any(), any());
        verify(indexingService, never()).reindex(any());
    }

    private FileMetadata metadata(long id) {
        FileMetadata metadata = FileMetadata.builder().fileName("file-" + id + ".txt").build();
        metadata.setId(id);
        metadata.setStatus(FileStatus.COMPLETED);
        return metadata;
    }
}
