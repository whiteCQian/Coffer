package com.coffer.tool;

import com.coffer.file.application.parse.DocumentParseService;
import com.coffer.service.MinioStorageService;
import com.coffer.file.domain.parse.ParseResult;
import com.coffer.file.domain.parse.ParseStatus;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * 文件解析工具：供 LLM 调用，按文件 ID 从存储系统定位文件并提取纯文本内容。
 *
 * <p>通过 {@code parse_file} 工具，模型可读取已上传文件的内容，
 * 支持 PDF、Word（doc/docx）和 TXT 格式，结果以纯文本返回给模型。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileParsingTool {

    private final DocumentParseService documentParseService;
    private final MinioStorageService minioStorageService;

    /**
     * 解析指定文件 ID 对应的文件内容并提取纯文本。
     *
     * @param fileId 需要解析的文件 ID，用于从存储系统中定位并获取文件内容
     * @return 提取的纯文本内容；文件不存在或解析失败时返回错误信息字符串，不抛异常
     */
    @Tool(name = "parse_file",
            value = "当用户上传文件后需要提取文件内容进行后续分析时调用此工具，支持 PDF、Word 和 TXT 格式，输入文件ID返回纯文本内容，适用于需要获取文件全文用于摘要生成、标签分类或内容理解的场景")
    public String parseFile(@P("文件的唯一标识ID，从系统上传接口返回的任务响应中获取，用于定位存储系统中的具体文件") String fileId) {
        try {
            DocumentParseService.FileInfo fileInfo = documentParseService.getFileInfo(fileId);
            if (fileInfo == null) {
                return "文件不存在或已被删除";
            }
            if (fileInfo.storagePath() == null || fileInfo.storagePath().isBlank()) {
                return "文件尚未完成上传，缺少存储路径";
            }
            try (InputStream stream = minioStorageService.getFileStream(null, fileInfo.storagePath())) {
                ParseResult result = documentParseService.extractTextFromFile(fileInfo.fileName(), stream);
                if (result.getStatus() == ParseStatus.SUCCESS || result.getStatus() == ParseStatus.EMPTY_CONTENT) {
                    return result.getContent();
                }
                String detail = result.getErrorMessage() == null ? result.getStatus().name() : result.getErrorMessage();
                return "文件解析失败：" + result.getStatus() + "，" + detail;
            }
        } catch (Exception e) {
            log.error("解析文件异常 fileId={}: {}", fileId, e.getMessage(), e);
            return "文件解析失败：" + e.getMessage();
        }
    }
}
