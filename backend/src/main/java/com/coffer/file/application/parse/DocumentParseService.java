package com.coffer.file.application.parse;

import com.coffer.file.domain.FileMetadata;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.infrastructure.parse.DocumentParser;
import com.coffer.file.domain.parse.ParseResult;
import com.coffer.file.domain.parse.ParseStatus;
import com.coffer.file.infrastructure.parse.ParserFactory;
import com.coffer.util.TextTruncator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * 文档解析服务：整合 {@link ParserFactory} 与 {@link TextTruncator}，
 * 将任意解析失败统一收敛为 {@link ParseResult} 错误状态，不向上抛出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParseService {

    /** 解析器返回的空内容兜底文案，用于识别"内容为空"。 */
    private static final String EMPTY_MARKER = "（文档内容为空）";

    private final ParserFactory parserFactory;
    private final TextTruncator textTruncator;
    private final FileMetadataRepository fileMetadataRepository;

    /**
     * 从文件提取纯文本，超长时截断。
     *
     * @param fileName    文件名（用于识别扩展名）
     * @param inputStream 文件输入流
     * @return 解析结果（文本可能已截断）
     */
    public ParseResult extractTextFromFile(String fileName, InputStream inputStream) {
        try {
            String extension = extractExtension(fileName);
            if (extension.isEmpty()) {
                return ParseResult.error(ParseStatus.UNSUPPORTED, "无法识别文件类型");
            }

            DocumentParser parser;
            try {
                parser = parserFactory.getParser(extension);
            } catch (IllegalArgumentException e) {
                return ParseResult.error(ParseStatus.UNSUPPORTED, e.getMessage());
            }

            String content;
            try {
                content = parser.parseToString(inputStream);
            } catch (RuntimeException e) {
                log.warn("文档解析异常 fileName={}: {}", fileName, e.getMessage());
                return classifyParseError(e);
            }

            if (content == null || content.isBlank() || EMPTY_MARKER.equals(content)) {
                return ParseResult.empty();
            }

            TextTruncator.TruncationStats stats = textTruncator.truncateWithStats(content);
            if (stats.truncated()) {
                log.info("文本过长已截断 fileName={}, 原始 {} 字符 -> 截断 {} 字符",
                        fileName, stats.originalCharCount(), stats.text().length());
            }
            return ParseResult.builder()
                    .content(stats.text())
                    .charCount(stats.text().length())
                    .status(ParseStatus.SUCCESS)
                    .build();
        } catch (Exception e) {
            log.error("文件文本提取失败 fileName={}: {}", fileName, e.getMessage(), e);
            return ParseResult.error(ParseStatus.FAILED, e.getMessage());
        }
    }

    /**
     * 带备选编码兜底的文本提取：先按默认路径解析，若文本文件结果异常（UNSUPPORTED / FAILED），
     * 依次尝试 GBK、UTF-8 重新解码，任一成功即返回，全部失败则返回首次解析结果。
     *
     * @param fileName    文件名
     * @param inputStream 文件输入流（本方法一次性读入字节，可安全复用）
     * @return 解析结果
     */
    public ParseResult extractTextWithFallback(String fileName, InputStream inputStream) {
        byte[] data;
        try {
            data = inputStream.readAllBytes();
        } catch (IOException e) {
            log.error("读取文件流失败 fileName={}: {}", fileName, e.getMessage());
            return ParseResult.error(ParseStatus.FAILED, e.getMessage());
        }

        ParseResult result = extractTextFromFile(fileName, new ByteArrayInputStream(data));
        boolean isTextFile = "txt".equalsIgnoreCase(extractExtension(fileName));
        // 乱码启发式：UTF-8 宽松解码出现替换符 U+FFFD，通常意味着实际编码为 GBK
        boolean looksMojibake = isTextFile && result.getStatus() == ParseStatus.SUCCESS
                && result.getContent() != null && result.getContent().indexOf('�') >= 0;
        boolean canFallback = result.getStatus() == ParseStatus.UNSUPPORTED
                || (isTextFile && (result.getStatus() == ParseStatus.FAILED || looksMojibake));
        if (!canFallback || !isTextFile) {
            return result;
        }

        for (String encoding : new String[]{"GBK", "UTF-8"}) {
            ParseResult retry = parseTextWithEncoding(data, encoding);
            if (retry != null) {
                log.info("备选编码解析成功 fileName={}, charset={}", fileName, encoding);
                return retry;
            }
        }
        return result;
    }

    /**
     * 按指定字符集解码文本并截断；解码或截断失败返回 null。
     */
    private ParseResult parseTextWithEncoding(byte[] data, String charsetName) {
        try {
            String content = new String(data, Charset.forName(charsetName));
            if (content == null || content.isBlank()) {
                return ParseResult.empty();
            }
            TextTruncator.TruncationStats stats = textTruncator.truncateWithStats(content);
            return ParseResult.builder()
                    .content(stats.text())
                    .charCount(stats.text().length())
                    .status(ParseStatus.SUCCESS)
                    .build();
        } catch (Exception e) {
            log.debug("备选编码解析失败 charset={}: {}", charsetName, e.getMessage());
            return null;
        }
    }

    /**
     * 依据异常消息关键词归类解析错误。
     */
    private ParseResult classifyParseError(RuntimeException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("加密")) {
            return ParseResult.error(ParseStatus.ENCRYPTED, e.getMessage());
        }
        if (msg.contains("损坏") || msg.contains("解析失败")) {
            return ParseResult.error(ParseStatus.CORRUPTED, e.getMessage());
        }
        return ParseResult.error(ParseStatus.FAILED, e.getMessage());
    }

    /**
     * 手动提取文件名扩展名（最后一个点号之后），避免引入 commons-io 依赖。
     */
    private String extractExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1);
    }

    /**
     * 文件基本信息（文件名 + 存储路径），供解析工具按 ID 定位文件。
     */
    public record FileInfo(String fileName, String storagePath) {
    }

    /**
     * 按文件 ID 查询文件元信息（文件名、存储路径），供解析工具定位文件。
     *
     * @param fileId 文件唯一标识
     * @return 文件信息；文件不存在或 ID 非法时返回 null
     */
    public FileInfo getFileInfo(String fileId) {
        try {
            Long id = Long.valueOf(fileId);
            return fileMetadataRepository.findById(id)
                    .map(metadata -> new FileInfo(metadata.getFileName(), metadata.getStoragePath()))
                    .orElse(null);
        } catch (NumberFormatException e) {
            log.warn("文件ID格式非法: {}", fileId);
            return null;
        }
    }
}
