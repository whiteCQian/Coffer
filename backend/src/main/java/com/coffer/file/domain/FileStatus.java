package com.coffer.file.domain;

/**
 * 文件处理状态枚举。
 *
 * <p>描述文件上传后进入 Agent 异步分析管道的生命周期状态，
 * 通过 {@code @Enumerated(EnumType.STRING)} 以字符串形式持久化。
 */
public enum FileStatus {

    /** 待处理：文件已上传，等待进入异步分析队列。 */
    PENDING,

    /** 处理中：Agent 正在解析文件并生成标签/摘要。 */
    PROCESSING,

    /** 已完成：分析成功，标签与摘要已入库。 */
    COMPLETED,

    /** 处理失败：分析过程中发生异常。 */
    FAILED
}
