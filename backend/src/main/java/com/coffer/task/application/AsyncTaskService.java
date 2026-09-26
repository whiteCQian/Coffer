package com.coffer.task.application;

import com.coffer.task.domain.AsyncTask;
import com.coffer.task.domain.AsyncTaskStatus;
import com.coffer.task.infrastructure.persistence.AsyncTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 异步任务状态流转服务：按 {@code PENDING -> PROCESSING -> COMPLETED/FAILED}
 * 流程统一维护 {@link AsyncTask} 状态，含合法性校验与原子性保证。
 *
 * <p>状态规则：终态（COMPLETED / FAILED）不可再变更，否则抛出
 * {@link IllegalStateException}；其余流转（含 PENDING 直转 FAILED 的失败场景）均合法。
 * 任务不存在时更新为静默忽略（仅告警日志），不抛异常。
 */
@Slf4j
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class AsyncTaskService {

    private final AsyncTaskRepository asyncTaskRepository;

    /**
     * 通用状态更新：按任务 ID 定位任务，存在则校验状态转换合法性并持久化。
     *
     * @param taskId   任务 ID
     * @param status   目标状态
     * @param progress 进度（非空才更新，避免便捷方法误清零）
     * @param result   处理结果（成功为摘要，失败为错误信息）
     * @throws IllegalStateException 状态转换非法（如从终态变更）时抛出
     */
    @Transactional
    public void updateTaskStatus(String taskId, AsyncTaskStatus status, Integer progress, String result) {
        AsyncTask task = asyncTaskRepository.findByTaskId(taskId).orElse(null);
        if (task == null) {
            log.warn("更新任务状态失败：任务不存在，已忽略 taskId={}", taskId);
            return;
        }
        AsyncTaskStatus current = task.getStatus();
        validateTransition(current, status, taskId);
        task.setStatus(status);
        if (progress != null) {
            task.setProgress(progress);
        }
        task.setResult(result);
        asyncTaskRepository.save(task);
        log.info("任务状态已更新 taskId={}, {} -> {}, 进度={}", taskId, current, status, progress);
    }

    /**
     * 便捷方法：任务置为处理中。
     *
     * @param taskId 任务 ID
     */
    @Transactional
    public void markAsProcessing(String taskId) {
        updateTaskStatus(taskId, AsyncTaskStatus.PROCESSING, null, null);
    }

    /**
     * 便捷方法：任务置为已完成（进度 100），result 写入摘要。
     *
     * @param taskId 任务 ID
     * @param result 处理结果（摘要）
     */
    @Transactional
    public void markAsCompleted(String taskId, String result) {
        updateTaskStatus(taskId, AsyncTaskStatus.COMPLETED, 100, result);
    }

    /**
     * 便捷方法：任务置为失败，result 写入错误信息。
     *
     * @param taskId 任务 ID
     * @param error  错误信息
     */
    @Transactional
    public void markAsFailed(String taskId, String error) {
        updateTaskStatus(taskId, AsyncTaskStatus.FAILED, null, error);
    }

    /**
     * 状态转换合法性校验：终态（COMPLETED/FAILED）为最终状态，禁止任何再变更；
     * 允许 PENDING/PROCESSING 之间的流转及向终态的收敛（含 PENDING 直转 FAILED）。
     *
     * @param current 当前状态
     * @param target  目标状态
     * @param taskId  任务 ID（用于异常信息定位）
     * @throws IllegalStateException 从终态发起转换时抛出
     */
    private void validateTransition(AsyncTaskStatus current, AsyncTaskStatus target, String taskId) {
        if (current == AsyncTaskStatus.COMPLETED || current == AsyncTaskStatus.FAILED) {
            throw new IllegalStateException("非法状态转换: " + current + " -> " + target
                    + "，终态不可再变更 (taskId=" + taskId + ")");
        }
        if (current == target) {
            log.debug("任务状态未变化，仍为 {} taskId={}", current, taskId);
        }
    }
}
