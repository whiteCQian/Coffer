package com.coffer.service;

import com.coffer.entity.*;
import com.coffer.repository.VectorCleanupTaskRepository;
import com.coffer.vector.VectorIndexCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j @com.coffer.auth.service.OwnerOnly
@Service @RequiredArgsConstructor
public class VectorCleanupService {
    private final VectorCleanupTaskRepository repository;
    private final VectorIndexCoordinator coordinator;
    @Value("${coffer.vector-store.cleanup.page-size:100}") private int pageSize;
    @Value("${coffer.vector-store.cleanup.max-backoff-ms:3600000}") private long maxBackoffMs;

    @Transactional
    public void enqueue(Long fileId) {
        if (!repository.existsByFileIdAndStatusNot(fileId, VectorCleanupStatus.COMPLETED)) {
            repository.save(VectorCleanupTask.builder().fileId(fileId)
                    .status(VectorCleanupStatus.PENDING).nextAttemptAt(LocalDateTime.now()).build());
        }
    }

    @com.coffer.auth.service.OwnerScheduled
    @Scheduled(fixedDelayString = "${coffer.vector-store.cleanup.fixed-delay-ms:60000}")
    public void processDueTasks() {
        var tasks = repository.findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                VectorCleanupStatus.PENDING, LocalDateTime.now(), PageRequest.of(0, pageSize));
        for (VectorCleanupTask task : tasks) {
            try {
                coordinator.deleteFile(task.getFileId());
                task.setStatus(VectorCleanupStatus.COMPLETED);
                task.setLastError(null);
            } catch (Exception e) {
                task.setAttempts(task.getAttempts() + 1);
                task.setLastError("向量索引清理失败，请稍后重试");
                long delay = Math.min(maxBackoffMs, 1_000L << Math.min(task.getAttempts(), 30));
                task.setNextAttemptAt(LocalDateTime.now().plusNanos(delay * 1_000_000L));
            }
            repository.save(task);
        }
    }
}
