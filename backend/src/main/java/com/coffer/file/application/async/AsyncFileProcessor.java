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
@Service
@RequiredArgsConstructor
public class AsyncFileProcessor {

    private final UploadPipelineService uploadPipelineService;

    /**
     * 异步触发上传分析管道。
     *
     * @param taskId 异步任务 ID（FileMetadata.taskId）
     */
    @Async("taskExecutor")
    public void processFileAsync(String taskId) {
        try {
            uploadPipelineService.processUploadPipeline(taskId);
        } catch (Exception e) {
            // 管道内部已吞掉业务异常，此处兜底确保任何异常不向外传播
            log.error("异步处理文件失败，taskId: {}", taskId, e);
        }
    }
}
