package com.coffer.file.application.async;

import com.coffer.file.application.UploadPipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 异步文件处理门面：作为上传自动分析管道的异步统一入口，
 * 将任务提交到 {@code taskExecutor} 线程池执行，异常不外泄。
 *
 * <p>Controller 上传接口同步创建任务后调用 {@link #processFileAsync(String)} 立即返回，
 * 由独立线程执行 {@link UploadPipelineService#processUploadPipeline(String)} 全流程。
 */
@Slf4j
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class AsyncFileProcessor {

    private final UploadPipelineService uploadPipelineService;
    private final com.coffer.task.infrastructure.persistence.AsyncTaskRepository tasks;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("taskExecutor")
    private java.util.concurrent.Executor executor;

    /**
     * 异步触发上传分析管道。
     *
     * @param taskId 异步任务 ID（FileMetadata.taskId）
     */
    public void processFileAsync(String taskId) {
        var task = tasks.findByTaskId(taskId)
                .orElseThrow(com.coffer.auth.service.ResourceNotFoundException::new);
        Long ownerId = task.getOwnerId();
        executor.execute(() -> com.coffer.auth.service.TenantContext.runAs(ownerId, () -> {
            try { uploadPipelineService.processUploadPipeline(taskId); }
            catch (Exception failure) {
                log.warn("文件处理任务未执行，异常类型={}", failure.getClass().getSimpleName());
            }
        }));
    }
}
