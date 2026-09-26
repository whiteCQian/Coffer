package com.coffer.task.application;

import com.coffer.task.domain.AsyncTask;
import com.coffer.task.domain.AsyncTaskStatus;
import com.coffer.task.infrastructure.persistence.AsyncTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务状态流转服务集成测试：验证
 * PENDING -> PROCESSING -> COMPLETED 正流向、PENDING 直转 FAILED 失败场景、
 * 终态不可再变更的合法性校验，以及任务不存在时的静默忽略。
 *
 * <p>每个用例在事务回滚中清理种子数据；独立 taskId 同时避免共享上下文时发生主键冲突。
 */
@SpringBootTest
@Transactional
class AsyncTaskServiceTest extends com.coffer.auth.OwnerTestSupport {

    @Autowired
    private AsyncTaskService asyncTaskService;

    @Autowired
    private AsyncTaskRepository repository;

    @Test
    void statusFlowPendingProcessingCompleted() {
        repository.save(AsyncTask.builder().taskId("t-flow-1")
                .status(AsyncTaskStatus.PENDING).progress(0).build());

        asyncTaskService.markAsProcessing("t-flow-1");
        assertThat(repository.findByTaskId("t-flow-1").orElseThrow().getStatus())
                .isEqualTo(AsyncTaskStatus.PROCESSING);

        asyncTaskService.markAsCompleted("t-flow-1", "解析摘要");
        AsyncTask completed = repository.findByTaskId("t-flow-1").orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
        assertThat(completed.getProgress()).isEqualTo(100);
        assertThat(completed.getResult()).isEqualTo("解析摘要");
    }

    @Test
    void markFailedFromPendingIsAllowed() {
        repository.save(AsyncTask.builder().taskId("t-fail-1")
                .status(AsyncTaskStatus.PENDING).build());

        asyncTaskService.markAsFailed("t-fail-1", "解析失败");

        AsyncTask failed = repository.findByTaskId("t-fail-1").orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(AsyncTaskStatus.FAILED);
        assertThat(failed.getResult()).isEqualTo("解析失败");
    }

    @Test
    void completedToProcessingThrowsIllegalState() {
        repository.save(AsyncTask.builder().taskId("t-term-1")
                .status(AsyncTaskStatus.COMPLETED).progress(100).build());

        assertThatThrownBy(() -> asyncTaskService
                .updateTaskStatus("t-term-1", AsyncTaskStatus.PROCESSING, 10, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法状态转换");

        // 终态未被破坏
        AsyncTask after = repository.findByTaskId("t-term-1").orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
    }

    @Test
    void updateOnMissingTaskIsNoOp() {
        asyncTaskService.markAsCompleted("t-missing-1", "x");
        assertThat(repository.findByTaskId("t-missing-1")).isEmpty();
    }
}
