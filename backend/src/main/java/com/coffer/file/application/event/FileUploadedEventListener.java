package com.coffer.file.application.event;

import com.coffer.file.application.async.AsyncFileProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Starts upload analysis only after metadata and task rows are committed. */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileUploadedEventListener {

    private final AsyncFileProcessor asyncFileProcessor;

    @com.coffer.auth.service.OwnedJob
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUploaded(FileUploadedEvent event) {
        log.info("上传事务已提交，触发异步文件处理 taskId={}", event.taskId());
        asyncFileProcessor.processFileAsync(event.taskId());
    }
}
