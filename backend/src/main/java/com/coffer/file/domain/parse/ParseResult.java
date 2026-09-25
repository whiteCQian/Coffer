package com.coffer.file.domain.parse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档解析结果封装。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParseResult {

    /** 提取出的纯文本内容。 */
    private String content;

    /** 文本字符数。 */
    private int charCount;

    /** 解析状态。 */
    private ParseStatus status;

    /** 解析失败时的错误信息。 */
    private String errorMessage;

    /**
     * 解析成功。
     *
     * @param content 提取的文本
     * @return SUCCESS 状态结果
     */
    public static ParseResult success(String content) {
        String safe = content == null ? "" : content;
        return ParseResult.builder()
                .content(safe)
                .charCount(safe.length())
                .status(ParseStatus.SUCCESS)
                .build();
    }

    /**
     * 内容为空。
     *
     * @return EMPTY_CONTENT 状态结果
     */
    public static ParseResult empty() {
        return ParseResult.builder()
                .content("（文档内容为空）")
                .charCount(0)
                .status(ParseStatus.EMPTY_CONTENT)
                .build();
    }

    /**
     * 解析失败。
     *
     * @param status       错误状态
     * @param errorMessage 错误信息
     * @return 指定错误状态结果
     */
    public static ParseResult error(ParseStatus status, String errorMessage) {
        return ParseResult.builder()
                .status(status)
                .errorMessage(errorMessage)
                .build();
    }
}
