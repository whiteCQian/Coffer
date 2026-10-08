package com.coffer.file.application.parse;

import com.coffer.file.domain.FileMetadata;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.domain.parse.ParseResult;
import com.coffer.file.domain.parse.ParseStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * All import and tool parse paths share one bounded, signature-checked parser.
 */
@Slf4j
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class DocumentParseService {

    /** 解析器返回的空内容兜底文案，用于识别"内容为空"。 */
    private static final String EMPTY_MARKER = "（文档内容为空）";

    private final FileMetadataRepository fileMetadataRepository;
    private final BoundedDocumentParser boundedParser;

    public com.coffer.file.domain.parse.ParsedDocument parseStructured(FileMetadata file, InputStream input) {
        return boundedParser.parse(file == null ? null : file.getId(),
                file == null || file.getRevision() == null ? 0 : file.getRevision(),
                file == null ? null : file.getFileName(), input);
    }

    /**
     * Compatibility view of the structured parse result for older callers.
     *
     * @param fileName    文件名（用于识别扩展名）
     * @param inputStream 文件输入流
     * @return explicit status and source chunks; oversized content is rejected
     */
    public ParseResult extractTextFromFile(String fileName, InputStream inputStream) {
        var document = boundedParser.parse(null, 0, fileName, inputStream);
        String content = document.status() == ParseStatus.EMPTY_CONTENT ? EMPTY_MARKER : document.content();
        return ParseResult.builder().document(document).content(content).charCount(content.length())
                .status(document.status()).errorMessage(safeMessage(document.status())).build();
    }

    private static String safeMessage(ParseStatus status) {
        return switch (status) {
            case ENCRYPTED -> "文档已加密，无法解析";
            case CORRUPTED -> "文档损坏，无法解析";
            case TYPE_MISMATCH -> "文件内容与扩展名不符";
            case LIMIT_EXCEEDED -> "文件超过处理资源上限";
            case UNSUPPORTED -> "不支持的文件类型";
            case FAILED -> "文件解析失败";
            default -> null;
        };
    }

    /** GBK fallback is performed inside the same bounded TXT parser. */
    public ParseResult extractTextWithFallback(String fileName, InputStream inputStream) {
        return extractTextFromFile(fileName, inputStream);
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
