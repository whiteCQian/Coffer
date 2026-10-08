package com.coffer.tool;

import com.coffer.auth.service.OwnerAuthorization;
import com.coffer.auth.service.ResourceNotFoundException;
import com.coffer.file.application.parse.DocumentParseService;
import com.coffer.file.storage.FileStoragePort;
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
    private static final int MAX_TOOL_CHARS = 16_000;
    private static final int MAX_TOOL_CHUNKS = 64;
    private final DocumentParseService parser;
    private final FileStoragePort storage;
    private final PrivateFileAccess files;
    private final OwnerAuthorization authorization;
    private final ChatCitationCollector citations;
    @org.springframework.beans.factory.annotation.Autowired
    private com.coffer.model.runtime.ModelContentGate contentGate;
    @org.springframework.beans.factory.annotation.Autowired
    private com.coffer.file.application.parse.ParsedDocumentStore parsedStore;

    @Tool(name = "parse_file", value = "按文件 ID 读取本账号文件的纯文本内容")
    public String parseFile(@P("文件 ID") String id) { return parseFile(authorization.requireOwner(), id); }

    public String parseFile(Long ownerId, String id) {
        if (!authorization.requireOwner().equals(ownerId)) throw new AccessDeniedException("无权执行此操作");
        try {
            var file = files.require(Long.valueOf(id));
            Long revision = file.getRevision();
            try (InputStream input = com.coffer.file.application.VerifiedFileSource.open(storage, file)) {
                var result = parser.parseStructured(file, input);
                files.requireVersion(file.getId(), revision);
                if (parsedStore != null && file.getContentSha256() != null) parsedStore.save(file, result);
                if (result.status() != ParseStatus.SUCCESS && result.status() != ParseStatus.EMPTY_CONTENT)
                    return "文件解析失败，请稍后重试";
                String content = result.content();
                java.util.Objects.requireNonNull(contentGate, "模型内容授权组件不可用").requireAllowed(file, content, true,
                        com.coffer.model.runtime.ModelRuntimeCapability.CHAT);
                StringBuilder selected = new StringBuilder();
                int captured = 0;
                for (var chunk : result.chunks()) {
                    if (chunk.text().isEmpty() || captured >= MAX_TOOL_CHUNKS
                            || selected.length() >= MAX_TOOL_CHARS) break;
                    if (!selected.isEmpty()) selected.append('\n');
                    int remaining = MAX_TOOL_CHARS - selected.length();
                    if (remaining <= 0) break;
                    selected.append(chunk.text(), 0, Math.min(chunk.text().length(), remaining));
                    citations.captureParsed(file, 1d, result, chunk);
                    captured++;
                }
                if (selected.length() < content.length())
                    selected.append("\n（内容已截断，请从文件预览查看全文）");
                return selected.toString();
            }
        } catch (ResourceNotFoundException | NumberFormatException missing) {
            return "文件不存在或已被删除";
        } catch (AccessDeniedException | com.coffer.model.runtime.ModelConsentRequiredException forbidden) { throw forbidden; }
        catch (Exception failure) { return "文件解析失败，请稍后重试"; }
    }
}
