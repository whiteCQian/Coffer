package com.coffer.file.infrastructure.parse;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * TXT 文本解析器，逐行读取并拼接为字符串。
 */
@Component
public class TxtParser implements DocumentParser {

    /** 解析失败时的错误提示。 */
    private static final String ERROR_MESSAGE = "TXT 文件解析失败";

    @Override
    public String parseToString(InputStream inputStream) {
        if (inputStream == null) {
            throw new IllegalArgumentException("输入流不能为空");
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append(System.lineSeparator());
            }
        } catch (IOException e) {
            throw new RuntimeException(ERROR_MESSAGE, e);
        }
        return sb.toString();
    }

    @Override
    public String getFileExtension() {
        return "txt";
    }
}
