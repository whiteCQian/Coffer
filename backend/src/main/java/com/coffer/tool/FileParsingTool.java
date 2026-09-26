package com.coffer.tool;

import com.coffer.auth.service.OwnerAuthorization;
import com.coffer.auth.service.ResourceNotFoundException;
import com.coffer.file.application.parse.DocumentParseService;
import com.coffer.service.MinioStorageService;
import com.coffer.service.PrivateFileAccess;
import com.coffer.service.ChatCitationCollector;
import com.coffer.file.domain.parse.ParseStatus;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.security.access.AccessDeniedException;
import java.io.InputStream;

@Component
@RequiredArgsConstructor
public class FileParsingTool {
    private final DocumentParseService parser;
    private final MinioStorageService storage;
    private final PrivateFileAccess files;
    private final OwnerAuthorization authorization;
    private final ChatCitationCollector citations;

    @Tool(name = "parse_file", value = "按文件 ID 读取本账号文件的纯文本内容")
    public String parseFile(@P("文件 ID") String id) { return parseFile(authorization.requireOwner(), id); }

    public String parseFile(Long ownerId, String id) {
        if (!authorization.requireOwner().equals(ownerId)) throw new AccessDeniedException("无权执行此操作");
        try {
            var file = files.require(Long.valueOf(id));
            Long revision = file.getRevision();
            try (InputStream input = storage.getFileStream(null, file.getStoragePath())) {
                var result = parser.extractTextFromFile(file.getFileName(), input);
                files.requireVersion(file.getId(), revision);
                if (result.getStatus() != ParseStatus.SUCCESS && result.getStatus() != ParseStatus.EMPTY_CONTENT)
                    return "文件解析失败，请稍后重试";
                String content = result.getContent() == null ? "" : result.getContent();
                citations.capture(file, 1d, "PARSE", content.substring(0, Math.min(120, content.length())));
                return content;
            }
        } catch (ResourceNotFoundException | NumberFormatException missing) {
            return "文件不存在或已被删除";
        } catch (AccessDeniedException forbidden) { throw forbidden; }
        catch (Exception failure) { return "文件解析失败，请稍后重试"; }
    }
}
