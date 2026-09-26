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

    @Tool(name = "search_files", value = "按关键词搜索本账号文件与已确认标签")
    public String searchFiles(@P("关键词") String keyword) { return searchFiles(authorization.requireOwner(), keyword); }
    public String searchFiles(Long ownerId, String keyword) {
        require(ownerId);
        if (keyword == null || keyword.isBlank()) return "搜索关键词不能为空";
        List<String> result = new ArrayList<>();
        for (var hit : search.searchWithEvidence(ownerId, keyword.trim())) {
            try {
                var file = access.requireVersion(hit.file().getId(), hit.file().getRevision());
                result.add(format(file, ownerId, hit.score(), hit.retrievalType()));
            } catch (ResourceNotFoundException | FileVersionConflictException ignored) { }
            if (result.size() == 10) break;
        }
        return result.isEmpty() ? "未找到匹配的文件" : String.join("\n", result);
    }

    @Tool(name = "get_recent_uploads", value = "获取本账号最近上传的 8 个文件")
    public String getRecentUploads() { return getRecentUploads(authorization.requireOwner()); }
    public String getRecentUploads(Long ownerId) {
        require(ownerId);
        List<String> result = new ArrayList<>();
        for (var candidate : files.findRecentFiles(PageRequest.of(0, 8))) {
            try {
                var file = access.requireVersion(candidate.getId(), candidate.getRevision());
                result.add(format(file, ownerId, 1d / (result.size() + 1), "RECENT_UPLOAD"));
            } catch (ResourceNotFoundException | FileVersionConflictException ignored) { }
        }
        return result.isEmpty() ? "当前还没有上传任何文件" : String.join("\n", result);
    }
    private void require(Long owner) {
        if (!authorization.requireOwner().equals(owner)) throw new AccessDeniedException("无权执行此操作");
    }
    private String format(FileMetadata file, Long owner, double score, String type) {
        List<Long> ids = mappings.findByFileIdAndConfirmationStatus(file.getId(), ConfirmationStatus.CONFIRMED).stream()
                .filter(m -> owner.equals(m.getOwnerId()) && file.getId().equals(m.getFileId()))
                .map(m -> m.getTagId()).distinct().toList();
        String names = ids.isEmpty() ? "暂无" : tags.findAllById(ids).stream()
                .filter(t -> owner.equals(t.getOwnerId())).map(t -> t.getTagName()).collect(Collectors.joining(","));
        String summary = file.getSummary() == null ? "暂无" : file.getSummary();
        String snippet = summary.substring(0, Math.min(120, summary.length()));
        // Revalidate after the tag queries, immediately before exposing the result.
        access.requireVersion(file.getId(), file.getRevision());
        citations.capture(file, score, type, snippet);
        return "文件ID：" + file.getId() + "，版本：" + file.getRevision() + "，文件名：" + file.getFileName()
                + "，类型：" + file.getFileType() + "，上传时间：" + file.getUploadTime()
                + "，状态：" + file.getStatus() + "，标签：" + names + "，摘要：" + summary;
    }
}
