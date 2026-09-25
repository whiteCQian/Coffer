package com.coffer.task;

import com.coffer.config.EmbeddingProperties;
import com.coffer.file.domain.FileStatus;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.vector.VectorIndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.coffer.vector.VectorIndexCoordinator;

/** Retries vector indexing for completed files after a Redis outage. */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorReindexTask {

    private static final int BATCH_SIZE = 20;

    private final EmbeddingProperties embeddingProperties;
    private final FileMetadataRepository fileMetadataRepository;
    private final VectorIndexingService vectorIndexingService;
    @Autowired(required = false)
    private VectorIndexCoordinator coordinator;

    @Scheduled(fixedDelayString = "${coffer.vector-store.reindex.fixed-delay-ms:300000}")
    public void reindexPendingFiles() {
        if (!embeddingProperties.isEnabled()) {
            return;
        }
        var pending = fileMetadataRepository.findByStatusAndVectorIndexedAtIsNull(
                FileStatus.COMPLETED, PageRequest.of(0, BATCH_SIZE));
        pending.forEach(file -> {
            boolean indexed = coordinator == null ? vectorIndexingService.reindex(file) : coordinator.reindex(file);
            if (indexed) {
                fileMetadataRepository.save(file);
            }
        });
    }
}
