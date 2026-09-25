package com.coffer.file.infrastructure.parse;

import java.io.InputStream;

/**
 * 文档解析器接口。
 *
 * <p>各文件格式实现该接口，供 {@code ParserFactory} 依据文件扩展名匹配对应解析器。
 */
public interface DocumentParser {

    /**
     * 将输入流解析为纯文本字符串。
     *
     * @param inputStream 文件输入流
     * @return 解析出的文本内容
     */
    String parseToString(InputStream inputStream);

    /**
     * 返回该解析器支持的文件扩展名（不含点号）。
     *
     * @return 扩展名，如 txt、pdf；无默认值时返回空串，由实现类覆写
     */
    default String getFileExtension() {
        return "";
    }
}
