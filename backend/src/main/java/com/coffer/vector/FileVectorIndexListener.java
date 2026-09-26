package com.coffer.vector;

import com.coffer.file.domain.FileMetadata;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Starts vector indexing only after the upload transaction has committed. */
@Slf4j @Component @RequiredArgsConstructor
public class FileVectorIndexListener {
    private final FileMetadataRepository repository;
    private final VectorIndexCoordinator coordinator;

    @Async("vectorIndexExecutor")
    @com.coffer.auth.service.OwnedJob
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(FileVectorIndexRequested event) {
        FileMetadata metadata = repository.findById(event.fileId()).orElse(null);
        if (metadata == null || metadata.getStatus() != com.coffer.file.domain.FileStatus.COMPLETED) return;
        if (coordinator.reindex(metadata)) {
            markIndexed(metadata);
        }
    }

    @Transactional
    public void markIndexed(FileMetadata metadata) {
        repository.save(metadata);
    }
}
