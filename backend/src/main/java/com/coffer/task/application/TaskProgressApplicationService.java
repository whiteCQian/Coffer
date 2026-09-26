package com.coffer.task.application;

import com.coffer.task.api.dto.TaskProgressResponse;
import com.coffer.task.domain.AsyncTask;
import com.coffer.task.infrastructure.persistence.AsyncTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application service for querying asynchronous task progress. */
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class TaskProgressApplicationService {

    private final AsyncTaskRepository asyncTaskRepository;

    /**
     * Loads a task snapshot for the public progress endpoint.
     *
     * @param taskId task identifier returned by the upload endpoint
     * @return progress response
     * @throws IllegalArgumentException when the task does not exist
     */
    @Transactional(readOnly = true)
    public TaskProgressResponse getTaskProgress(String taskId) {
        AsyncTask task = asyncTaskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new com.coffer.auth.service.ResourceNotFoundException());
        return toResponse(task);
    }

    private TaskProgressResponse toResponse(AsyncTask task) {
        return TaskProgressResponse.builder()
                .taskId(task.getTaskId())
                .fileName(task.getFileName())
                .runMode(task.getRunMode())
                .modelSnapshotId(task.getModelSnapshotId())
                .status(task.getStatus() == null ? null : task.getStatus().name())
                .progress(task.getProgress())
                .result(task.getResult())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build();
    }
}
