package com.coffer.file.application;

import com.coffer.file.application.async.AsyncFileProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Application-level orchestration for file rename, retry, and deletion use cases. */
@Slf4j
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class FileLifecycleApplicationService {

    private final FileOperationService fileOperationService;
    private final AsyncFileProcessor asyncFileProcessor;

    public void renameFile(Long id, String newName) {
        fileOperationService.renameFile(id, newName);
    }

    public void retryFile(Long id) {
        String taskId = fileOperationService.retryFile(id);
        asyncFileProcessor.processFileAsync(taskId);
        log.info("文件重试已触发异步解析 fileId={}, newTaskId={}", id, taskId);
    }

    public void deleteFile(Long id) {
        fileOperationService.deleteFile(id);
        log.info("文件删除及持久化存储清理任务已登记 fileId={}", id);
    }
}
