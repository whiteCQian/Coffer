package com.coffer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 对话消息实体，存储用户与 AI 的对话记录，支撑短期对话记忆与指代消解。
 */
@Entity
@Table(name = "chat_message",
        indexes = {
                @Index(name = "idx_chat_message_session_id", columnList = "session_id"),
                @Index(name = "idx_chat_message_timestamp", columnList = "timestamp")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class ChatMessage extends com.coffer.auth.domain.TenantOwnedEntity {
    @Column(name = "model_snapshot_id", length = 36) private String modelSnapshotId;

    /** 自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 会话 ID，区分不同对话会话。 */
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    /** 用户消息，较长的自然语言查询。 */
    @Column(name = "user_message", columnDefinition = "TEXT")
    private String userMessage;

    /** AI 回复，模型返回的完整回答。 */
    @Column(name = "ai_response", columnDefinition = "TEXT")
    private String aiResponse;

    /** 消息时间，由 Hibernate 在插入时自动填充。 */
    @CreationTimestamp
    @Column(name = "timestamp", updatable = false)
    private LocalDateTime timestamp;
}
