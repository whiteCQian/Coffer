package com.coffer.task.domain;

import com.coffer.governance.domain.GovernanceRunMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 异步任务实体，追踪文件异步处理任务的状态，供前端轮询进度。
 *
 * <p>任务状态使用独立枚举 {@link AsyncTaskStatus}，与文件处理状态区分。
 */
@Entity
@Table(name = "async_task",
        indexes = {
                // task_id 已由 unique 约束提供唯一索引（可加速等值查询），无需重复建普通索引
                @Index(name = "idx_async_task_status", columnList = "status")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class AsyncTask {

    /** 自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 任务 ID，全局唯一，供前端通过任务 ID 轮询进度。 */
    @Column(name = "task_id", unique = true, nullable = false, length = 64)
    private String taskId;

    /** 文件名，用于显示当前处理哪个文件。 */
    @Column(name = "file_name", length = 255)
    private String fileName;

    /** Immutable runtime mode snapshot captured when the task was registered. */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "run_mode", nullable = false, length = 16)
    private GovernanceRunMode runMode = GovernanceRunMode.API;

    /** 任务状态，以字符串形式持久化。 */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 20)
    private AsyncTaskStatus status = AsyncTaskStatus.PENDING;

    /** 进度百分比，取值 0 到 100。 */
    @Builder.Default
    @Column(name = "progress")
    private Integer progress = 0;

    /** 处理结果：成功时为摘要，失败时为错误信息。 */
    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    /** 创建时间。 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间。 */
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
