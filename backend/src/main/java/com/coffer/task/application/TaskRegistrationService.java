package com.coffer.task.application;

import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.model.runtime.ModelRuntimeModeService;
import com.coffer.task.domain.AsyncTask;
import com.coffer.task.domain.AsyncTaskStatus;
import com.coffer.task.infrastructure.persistence.AsyncTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public task-boundary service for creating, replacing, validating, and deleting task records.
 */
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class TaskRegistrationService {

    private final AsyncTaskRepository asyncTaskRepository;

    @Autowired(required = false)
    private ModelRuntimeModeService runtimeModeService;

    @Transactional
    public void createPendingTask(String taskId, String fileName) {
        asyncTaskRepository.save(AsyncTask.builder()
                .taskId(taskId)
                .fileName(fileName)
                .runMode(captureMode())
                .modelSnapshotId(com.coffer.model.runtime.ModelExecutionContext.currentId())
                .status(AsyncTaskStatus.PENDING)
                .progress(0)
                .build());
    }

    @Transactional
    public void replaceWithPendingTask(String oldTaskId, String newTaskId, String fileName) {
        deleteTaskIfPresent(oldTaskId);
        createPendingTask(newTaskId, fileName);
    }

    @Transactional
    public void deleteTaskIfPresent(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        asyncTaskRepository.findByTaskId(taskId).ifPresent(asyncTaskRepository::delete);
    }

    @Transactional(readOnly = true)
    public void requireExistingTask(String taskId) {
        if (asyncTaskRepository.findByTaskId(taskId).isEmpty()) {
            throw new com.coffer.auth.service.ResourceNotFoundException();
        }
    }

    @Transactional(readOnly = true)
    public java.util.Optional<GovernanceRunMode> findRunMode(String taskId) {
        return asyncTaskRepository.findByTaskId(taskId)
                .map(task -> task.getRunMode() == null ? GovernanceRunMode.API : task.getRunMode());
    }

    private GovernanceRunMode captureMode() {
        return runtimeModeService == null ? GovernanceRunMode.API : runtimeModeService.requireActiveMode();
    }

    public String findSnapshotId(String taskId) {
        return asyncTaskRepository.findByTaskId(taskId).orElseThrow(com.coffer.auth.service.ResourceNotFoundException::new).getModelSnapshotId();
    }
}
