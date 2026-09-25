package com.coffer.service;

import com.coffer.entity.*;
import com.coffer.config.EmbeddingProperties;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.repository.VectorReindexJobRepository;
import com.coffer.vector.RedisVectorStore;
import com.coffer.vector.VectorIndexCoordinator;
import com.coffer.vector.VectorIndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Executor;

@Slf4j @Service @RequiredArgsConstructor
public class VectorReindexService {
    private final VectorReindexJobRepository jobs;
    private final FileMetadataRepository files;
    private final VectorIndexingService indexing;
    private final RedisVectorStore store;
    private final VectorIndexCoordinator coordinator;
    private final EmbeddingProperties embeddingProperties;
    @Value("${coffer.vector-store.reindex.page-size:100}") private int pageSize;
    @Autowired @Qualifier("vectorIndexExecutor") private Executor vectorExecutor;

    @Transactional
    public VectorReindexJob start() {
        if (!embeddingProperties.isEnabled()) {
            throw new IllegalStateException("Embedding 未启用，无法执行向量重建");
        }
        var running = jobs.findByStatus(VectorReindexStatus.RUNNING);
        if (!running.isEmpty()) return running.get(0);
        String id = UUID.randomUUID().toString();
        VectorReindexJob job = VectorReindexJob.builder().jobId(id).status(VectorReindexStatus.RUNNING)
                .totalCount((int) files.countByStatus(FileStatus.COMPLETED)).startedAt(LocalDateTime.now()).build();
        jobs.save(job);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { runAsync(id); }
        });
        return job;
    }

    /** Recover a persisted job after a process restart. */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeRunningJobs() {
        jobs.findByStatus(VectorReindexStatus.RUNNING).forEach(job -> {
            job.setStatus(VectorReindexStatus.FAILED);
            job.setFinishedAt(LocalDateTime.now());
            job.setErrorSummary("应用重启导致任务中断，请重新发起重建");
            jobs.save(job);
        });
    }

    @Async("vectorIndexExecutor")
    public void runAsync(String jobId) {
        coordinator.withRebuildLock(() -> { run(jobId); return null; });
    }

    private void run(String jobId) {
        VectorReindexJob job = jobs.findById(jobId).orElse(null);
        if (job == null) return;
        String generation = UUID.randomUUID().toString();
        String previousGeneration = store.activeGeneration();
        var indexedFiles = new java.util.ArrayList<FileMetadata>();
        int page = 0;
        try {
            while (true) {
                var batch = files.findByStatus(FileStatus.COMPLETED,
                        PageRequest.of(page++, pageSize, Sort.by(Sort.Direction.ASC, "id"))).getContent();
                if (batch.isEmpty()) break;
                for (FileMetadata file : batch) {
                    job.setProcessedCount(job.getProcessedCount() + 1);
                    if (file.getStoragePath() == null || isImage(file.getFileType())) {
                        job.setSkippedCount(job.getSkippedCount() + 1);
                    } else if (indexing.reindex(file, generation)) {
                        job.setSuccessCount(job.getSuccessCount() + 1);
                        // Do not persist the new generation marker until the
                        // temporary generation has passed validation and is active.
                        indexedFiles.add(file);
                    } else {
                        job.setFailedCount(job.getFailedCount() + 1);
                    }
                    jobs.save(job);
                }
            }
            if (job.getSuccessCount() > 0 && isValidGeneration(generation)) {
                store.setActiveGeneration(generation);
                indexedFiles.forEach(file -> {
                    file.setVectorIndexGeneration(generation);
                    file.setVectorIndexedAt(LocalDateTime.now());
                    files.save(file);
                });
                if (previousGeneration != null && !previousGeneration.equals(generation)) {
                    cleanupAsync(previousGeneration);
                }
            } else {
                if (job.getSuccessCount() > 0) {
                    job.setStatus(VectorReindexStatus.FAILED);
                    job.setErrorSummary("临时 generation 校验失败，保留旧活动索引");
                }
                cleanupAsync(generation);
            }
            if (job.getStatus() == VectorReindexStatus.RUNNING) {
                job.setStatus(job.getFailedCount() == 0 ? VectorReindexStatus.SUCCESS : VectorReindexStatus.PARTIAL);
            }
        } catch (Exception e) {
            job.setStatus(VectorReindexStatus.FAILED);
            job.setErrorSummary(e.getMessage());
            cleanupAsync(generation);
            log.error("向量全量重建失败 jobId={}: {}", jobId, e.getMessage(), e);
        } finally {
            job.setFinishedAt(LocalDateTime.now());
            jobs.save(job);
        }
    }

    public void cleanupAsync(String generation) {
        vectorExecutor.execute(() -> {
            try { store.deleteGeneration(generation); }
            catch (Exception e) { log.warn("旧向量 generation 清理失败 generation={}: {}", generation, e.getMessage()); }
        });
    }

    @Transactional(readOnly = true)
    public VectorReindexJob get(String jobId) { return jobs.findById(jobId).orElse(null); }

    private boolean isImage(String type) {
        return type != null && java.util.Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp").contains(type.toLowerCase());
    }

    private boolean isValidGeneration(String generation) {
        if (!store.hasGeneration(generation)) return false;
        Long actual = store.dimension(generation);
        return actual != null && embeddingProperties.getDimensions() != null
                && actual == embeddingProperties.getDimensions().longValue();
    }
}
