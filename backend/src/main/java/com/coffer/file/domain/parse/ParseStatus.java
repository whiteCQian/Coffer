package com.coffer.file.domain.parse;

/**
 * 文档解析状态枚举。
 */
public enum ParseStatus {

    /** 解析成功。 */
    SUCCESS,

    /** 内容为空。 */
    EMPTY_CONTENT,

    /** 文件已加密。 */
    ENCRYPTED,

    /** 文件损坏。 */
    CORRUPTED,

    /** 不支持的格式。 */
    UNSUPPORTED,

    /** The stream, page count, pixel count or extracted text exceeded the configured ceiling. */
    LIMIT_EXCEEDED,

    /** File bytes do not match the declared extension. */
    TYPE_MISMATCH,

    /** 未知失败。 */
    FAILED
}
