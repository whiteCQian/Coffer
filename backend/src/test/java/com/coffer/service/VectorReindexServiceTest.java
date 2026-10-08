package com.coffer.service;

import com.coffer.auth.service.TenantJobRunner;
import com.coffer.config.EmbeddingProperties;
import com.coffer.entity.VectorReindexJob;
import com.coffer.entity.VectorReindexStatus;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.model.runtime.ModelConsentRequiredException;
import com.coffer.model.runtime.ModelExecutionSnapshotService;
import com.coffer.repository.VectorReindexJobRepository;
import com.coffer.vector.RedisVectorStore;
import com.coffer.vector.VectorIndexCoordinator;
import com.coffer.vector.VectorIndexingService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VectorReindexServiceTest {
    private final VectorReindexJobRepository jobs = mock(VectorReindexJobRepository.class);
    private final VectorIndexCoordinator coordinator = mock(VectorIndexCoordinator.class);
    private final EmbeddingProperties properties = new EmbeddingProperties();
    private final VectorReindexService service = new VectorReindexService(jobs,
            mock(FileMetadataRepository.class), mock(VectorIndexingService.class),
            mock(TenantJobRunner.class), mock(RedisVectorStore.class), coordinator, properties);

    @Test void rebuildCannotStartWithoutCapturedModelDestination() {
        properties.setEnabled(true);
        when(jobs.findByStatus(VectorReindexStatus.RUNNING)).thenReturn(List.of());

        assertThatThrownBy(service::start).isInstanceOf(ModelConsentRequiredException.class);
        verify(jobs, never()).save(any());
    }

    @Test void missingSnapshotMarksQueuedJobFailedInsteadOfLeavingItRunning() {
        ReflectionTestUtils.setField(service, "snapshots", mock(ModelExecutionSnapshotService.class));
        var job = VectorReindexJob.builder().jobId("job-1").status(VectorReindexStatus.RUNNING).build();
        when(jobs.findById("job-1")).thenReturn(Optional.of(job));

        service.runAsync("job-1");

        assertThat(job.getStatus()).isEqualTo(VectorReindexStatus.FAILED);
        assertThat(job.getFinishedAt()).isNotNull();
        verify(jobs).save(job);
        verifyNoInteractions(coordinator);
    }
}
