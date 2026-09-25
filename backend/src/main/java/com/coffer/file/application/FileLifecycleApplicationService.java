package com.coffer.file.application;

import com.coffer.file.application.async.AsyncFileProcessor;
import com.coffer.service.MinioStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Application-level orchestration for file rename, category, retry, and deletion use cases. */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileLifecycleApplicationService {

    private final FileOperationService fileOperationService;
    private final MinioStorageService minioStorageService;
    private final AsyncFileProcessor asyncFileProcessor;

    public void renameFile(Long id, String newName) {
        fileOperationService.renameFile(id, newName);
    }

    public void changeCategory(Long id, String category) {
        fileOperationService.changeCategory(id, category);
    }

    public void retryFile(Long id) {
        String taskId = fileOperationService.retryFile(id);
        asyncFileProcessor.processFileAsync(taskId);
        log.info("文件重试已触发异步解析 fileId={}, newTaskId={}", id, taskId);
    }

    public void deleteFile(Long id) {
        String storagePath = fileOperationService.deleteFile(id);
        if (storagePath == null || storagePath.isBlank()) {
            return;
        }
        try {
            minioStorageService.deleteFile(null, storagePath);
            log.info("MinIO 对象已删除 storagePath={}", storagePath);
        } catch (Exception e) {
            log.warn("MinIO 对象删除失败（留孤儿可 GC）fileId={}, storagePath={}: {}",
                    id, storagePath, e.getMessage());
        }
    }
}
