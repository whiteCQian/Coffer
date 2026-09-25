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
public class ChatCitationCollector {

    private final ThreadLocal<LinkedHashMap<Long, ChatCitation>> current = new ThreadLocal<>();

    /** 开始收集一轮对话的引用。 */
    public void begin() {
        current.set(new LinkedHashMap<>());
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
        ChatCitation incoming = ChatCitation.builder()
                .fileId(file.getId())
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
        Map<Long, ChatCitation> captured = current.get();
        current.remove();
        return captured == null ? List.of() : List.copyOf(new ArrayList<>(captured.values()));
    }

    /** 异常或短路时清理本轮采集状态。 */
    public void clear() {
        current.remove();
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
