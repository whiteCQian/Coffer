package com.coffer.tag.application.event;

import com.coffer.file.application.archive.StorageArchiveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 标签确认后置归档监听器：在确认事务 {@code AFTER_COMMIT} 之后触发分类目录归档。
 *
 * <p>移动 MinIO 是外部 IO，必须在 DB 事务提交后进行（事务回滚则不触发），
 * 避免「DB 回滚但 MinIO 已搬」的不一致（见分类目录归档任务清单 T4.3）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TagConfirmedEventListener {

    private final StorageArchiveService storageArchiveService;

    /**
     * 事务提交后触发归档。归档自身吞异常，不影响确认结果。
     *
     * @param event 标签确认事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTagConfirmed(TagConfirmedEvent event) {
        log.info("标签确认事务已提交，触发归档 fileId={}", event.getFileId());
        storageArchiveService.archive(event.getFileId());
    }
}
