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

import java.time.LocalDateTime;

/**
 * 模型调用日志实体：记录每次请求 DeepSeek API 的 Token 消耗与调用结果，
 * 供成本统计与调用监控使用。
 */
@Entity
@Table(name = "model_call_log",
        indexes = {
                // 会话维度查询、定时清理按时间删除
                @Index(name = "idx_model_call_session", columnList = "session_id"),
                @Index(name = "idx_model_call_time", columnList = "call_time")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class ModelCallLog {

    /** 自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 会话标识，用于区分不同会话的调用记录。 */
    @Column(name = "session_id", length = 128)
    private String sessionId;

    /** 用户消息（请求内容），可能较长，使用 TEXT 存储。 */
    @Column(name = "user_message", columnDefinition = "TEXT")
    private String userMessage;

    /** AI 响应内容，可能较长，使用 TEXT 存储。 */
    @Column(name = "ai_response", columnDefinition = "TEXT")
    private String aiResponse;

    /** 输入 Token 数量。 */
    @Column(name = "prompt_tokens")
    private int promptTokens;

    /** 输出 Token 数量。 */
    @Column(name = "completion_tokens")
    private int completionTokens;

    /** 总 Token 消耗。 */
    @Column(name = "total_tokens")
    private int totalTokens;

    /** 当前调用重试次数（0 表示首次调用，1 表示第 1 次重试，依此类推）。 */
    @Column(name = "retry_count")
    private int retryCount;

    /** 响应耗时（毫秒）。 */
    @Column(name = "response_time_ms")
    private long responseTimeMs;

    /** 调用时间。 */
    @Column(name = "call_time", nullable = false)
    private LocalDateTime callTime;

    /** 使用的模型名称（如 deepseek-chat）。 */
    @Column(name = "model_name", length = 128)
    private String modelName;

    /** 调用状态：SUCCESS / FAILED。 */
    @Column(name = "status", length = 20)
    private String status;

    /** 错误信息，调用失败时记录。 */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
}
