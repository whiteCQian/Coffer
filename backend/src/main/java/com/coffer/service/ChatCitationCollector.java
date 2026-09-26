package com.coffer.service;

import com.coffer.dto.ChatCitation;
import com.coffer.file.domain.FileMetadata;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 收集当前对话轮次中由真实工具命中的文件来源。
 *
 * <p>Agent 工具调用和对话服务在同一请求线程内执行，因此使用请求线程隔离的
 * 短生命周期采集器，既不会把上一轮引用带入下一轮，也不需要让模型接口携带额外
 * 的内部状态。没有显式开启采集时，工具调用不会留下线程本地数据。</p>
 */
@Component
@lombok.RequiredArgsConstructor
public class ChatCitationCollector {

    private final PrivateFileAccess access;
    private final com.coffer.auth.service.OwnerAuthorization authorization;
    private final ThreadLocal<Long> owner = new ThreadLocal<>();

    private final ThreadLocal<LinkedHashMap<Long, ChatCitation>> current = new ThreadLocal<>();

    /** 开始收集一轮对话的引用。 */
    public void begin() {
        owner.set(authorization.requireOwner());
        current.set(new LinkedHashMap<>());
    }

    public boolean active() { return current.get() != null; }
    public Map<Long, Long> revisions() {
        checkOwner();
        Map<Long, Long> revisions = new LinkedHashMap<>();
        if (current.get() != null) current.get().values().forEach(c -> revisions.put(c.getFileId(), c.getRevision()));
        return revisions;
    }
    private void checkOwner() {
        Long caller = authorization.requireOwner();
        if (owner.get() != null && !owner.get().equals(caller))
            throw new org.springframework.security.access.AccessDeniedException("无权执行此操作");
    }

    /**
     * 收集一个真实文件命中，并按文件 ID 去重。
     *
     * <p>同一文件被多条检索路径命中时保留最大分数；检索类型合并后仍然能说明
     * 该文件来自哪些检索路径。</p>
     */
    public void capture(FileMetadata file, Double score, String retrievalType, String snippet) {
        if (file == null || file.getId() == null || current.get() == null) {
            return;
        }
        checkOwner();
        file = access.requireVersion(file.getId(), file.getRevision());
        ChatCitation incoming = ChatCitation.builder()
                .fileId(file.getId())
                .revision(file.getRevision())
                .contentUrl("/api/files/" + file.getId() + "/content?revision=" + file.getRevision())
                .fileName(file.getFileName())
                .fileType(file.getFileType())
                .snippet(snippet)
                .score(score)
                .retrievalType(retrievalType)
                .build();
        current.get().merge(file.getId(), incoming, this::merge);
    }

    /** 返回本轮引用并清理线程本地状态。 */
    public List<ChatCitation> finish() {
        try {
            checkOwner();
            Map<Long, ChatCitation> captured = current.get();
            return captured == null ? List.of() : captured.values().stream()
                    .filter(c -> access.current(c.getFileId(), c.getRevision())).toList();
        } finally { clear(); }
    }

    /** 异常或短路时清理本轮采集状态。 */
    public void clear() {
        current.remove();
        owner.remove();
    }

    private ChatCitation merge(ChatCitation previous, ChatCitation incoming) {
        Double score = previous.getScore();
        if (incoming.getScore() != null && (score == null || incoming.getScore() > score)) {
            score = incoming.getScore();
        }
        String retrievalType = mergeRetrievalType(previous.getRetrievalType(), incoming.getRetrievalType());
        String snippet = isBlank(previous.getSnippet()) ? incoming.getSnippet() : previous.getSnippet();
        return previous.toBuilder()
                .score(score)
                .retrievalType(retrievalType)
                .snippet(snippet)
                .build();
    }

    private String mergeRetrievalType(String previous, String incoming) {
        if (isBlank(previous)) {
            return incoming;
        }
        if (isBlank(incoming) || previous.equals(incoming) || previous.contains(incoming)) {
            return previous;
        }
        return previous + "," + incoming;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
