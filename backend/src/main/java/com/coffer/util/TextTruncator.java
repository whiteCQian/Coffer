package com.coffer.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 文本长度截断工具。
 *
 * <p>防止超长文本超出模型上下文窗口，超出最大长度时截取前缀并追加提示。
 */
@Component
public class TextTruncator {

    /** 截断提示后缀。 */
    private static final String TRUNCATE_SUFFIX = "\n...（内容过长已截断）";

    private final int maxLength;

    public TextTruncator(@Value("${coffer.max-token-text-length:10000}") int maxLength) {
        this.maxLength = maxLength;
    }

    /**
     * 截断文本：null 返回空串，未超长返回原文，超长截取前缀并追加提示。
     *
     * @param text 原始文本
     * @return 截断后的文本
     */
    public String truncate(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + TRUNCATE_SUFFIX;
    }

    /**
     * 截断并返回统计信息。
     *
     * @param text 原始文本
     * @return 截断后文本、原始字符数、是否被截断
     */
    public TruncationStats truncateWithStats(String text) {
        int originalCharCount = text == null ? 0 : text.length();
        boolean truncated = text != null && text.length() > maxLength;
        return new TruncationStats(truncate(text), originalCharCount, truncated);
    }

    /**
     * 截断统计结果。
     *
     * @param text             截断后的文本
     * @param originalCharCount 原始字符数
     * @param truncated         是否被截断
     */
    public record TruncationStats(String text, int originalCharCount, boolean truncated) {
    }
}
