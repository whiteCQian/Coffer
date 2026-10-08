package com.coffer.tool;

import com.coffer.auth.service.OwnerAuthorization;
import com.coffer.auth.service.ResourceNotFoundException;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.tag.infrastructure.persistence.TagRepository;
import com.coffer.tag.domain.ConfirmationStatus;
import com.coffer.service.*;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class FileSearchTool {
    private final FileMetadataRepository files;
    private final FileTagMappingRepository mappings;
    private final TagRepository tags;
    private final HybridSearchService search;
    private final PrivateFileAccess access;
    private final OwnerAuthorization authorization;
    private final ChatCitationCollector citations;
    @org.springframework.beans.factory.annotation.Autowired
    private com.coffer.model.runtime.ModelContentGate contentGate;

    @Tool(name = "search_files", value = "按关键词搜索本账号文件与已确认标签")
    public String searchFiles(@P("关键词") String keyword) { return searchFiles(authorization.requireOwner(), keyword); }
    public String searchFiles(Long ownerId, String keyword) {
        require(ownerId);
        if (keyword == null || keyword.isBlank()) return "搜索关键词不能为空";
        List<String> result = new ArrayList<>();
        boolean consentRequired = false;
        for (var hit : search.searchWithEvidence(ownerId, keyword.trim())) {
            try {
                var file = access.requireVersion(hit.file().getId(), hit.file().getRevision());
                result.add(format(file, ownerId, hit.score(), hit.retrievalType()));
            } catch (com.coffer.model.runtime.ModelConsentRequiredException blocked) {
                consentRequired = true;
            } catch (ResourceNotFoundException | FileVersionConflictException ignored) { }
            if (result.size() == 10) break;
        }
        return describeResults(result, consentRequired, "未找到匹配的文件");
    }

    @Tool(name = "get_recent_uploads", value = "获取本账号最近上传的 8 个文件")
    public String getRecentUploads() { return getRecentUploads(authorization.requireOwner()); }
    public String getRecentUploads(Long ownerId) {
        require(ownerId);
        List<String> result = new ArrayList<>();
        boolean consentRequired = false;
        for (var candidate : files.findRecentFiles(PageRequest.of(0, 8))) {
            try {
                var file = access.requireVersion(candidate.getId(), candidate.getRevision());
                result.add(format(file, ownerId, 1d / (result.size() + 1), "RECENT_UPLOAD"));
            } catch (com.coffer.model.runtime.ModelConsentRequiredException blocked) {
                consentRequired = true;
            } catch (ResourceNotFoundException | FileVersionConflictException ignored) { }
        }
        return describeResults(result, consentRequired, "当前还没有上传任何文件");
    }
    private String describeResults(List<String> result, boolean consentRequired, String emptyText) {
        String visible = result.isEmpty() ? emptyText : String.join("\n", result);
        return consentRequired ? visible + "\n部分文件尚未授权当前聊天模型。请在文件详情中查看目标并授权后重试。" : visible;
    }
    private void require(Long owner) {
        if (!authorization.requireOwner().equals(owner)) throw new AccessDeniedException("无权执行此操作");
    }
    private String format(FileMetadata file, Long owner, double score, String type) {
        // A search hit can enter a remote chat prompt. Classify its actual local
        // parse result instead of treating an empty placeholder as a safe source.
        java.util.Objects.requireNonNull(contentGate, "模型内容授权组件不可用").requireFileAllowed(file,
                com.coffer.model.runtime.ModelRuntimeCapability.CHAT);
        // Revalidate after the tag queries, immediately before exposing the result.
        access.requireVersion(file.getId(), file.getRevision());
        citations.capture(file, score, type, null);
        return "文件ID：" + file.getId() + "，版本：" + file.getRevision() + "，文件名：" + file.getFileName()
                + "，类型：" + file.getFileType() + "，上传时间：" + file.getUploadTime()
                + "，状态：" + file.getStatus();
    }
}
