package com.coffer.tag.application.event;

/**
 * 标签确认成功事件：在标签从待确认流转为已确认（事务提交后）触发分类目录归档。
 *
 * <p>由 {@code TagConfirmationService.confirmTag} 在真实迁移（PENDING → CONFIRMED）时发布，
 * 幂等早退分支不发布。经 {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 监听：
 * 事务提交后才移动 MinIO，事务回滚则不触发，保证移动与 DB 状态一致（见分类目录归档任务清单 T4.3）。
 *
 * <p>Spring 4.2+ 支持发布普通 POJO，无需继承 {@code ApplicationEvent}。
 */
public class TagConfirmedEvent {

    private final Long fileId;

    public TagConfirmedEvent(Long fileId) {
        this.fileId = fileId;
    }

    public Long getFileId() {
        return fileId;
    }
}
