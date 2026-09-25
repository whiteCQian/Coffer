package com.coffer.task.domain;

/**
 * 异步任务状态枚举。
 */
public enum AsyncTaskStatus {

    /** 待处理。 */
    PENDING,

    /** 处理中。 */
    PROCESSING,

    /** 已完成。 */
    COMPLETED,

    /** 失败。 */
    FAILED
}
