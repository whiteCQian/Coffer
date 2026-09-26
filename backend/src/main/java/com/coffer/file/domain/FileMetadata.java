package com.coffer.file.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 文件元数据实体，Coffer 智能存储系统的核心数据模型。
 *
 * <p>记录文件的基础信息（名称、大小、类型、存储位置）与
 * Agent 分析产生的扩展信息（AI 摘要、处理状态、异步任务关联）。
 *
 * <p>数据库表名 {@code file_metadata}，列名采用小写下划线风格，
 * 与项目规范保持一致；状态字段以字符串形式存储，便于人工理解与检索。
 */
@Entity
@Table(name = "file_metadata",
        indexes = {
                // 说明：file_name 已调整为 TEXT，普通 B-tree 索引无法直接建在 TEXT 列上；
                // 搜索改由 MySQL FULLTEXT 索引 idx_file_search 承担（见 docs/archive/sql/fulltext_search.sql），
                // 故此处不再为 file_name 声明单列索引。
                @Index(name = "idx_file_metadata_status", columnList = "status"),
                @Index(name = "idx_file_metadata_upload_time", columnList = "upload_time")
        })
@Data
@lombok.EqualsAndHashCode(callSuper = false)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileMetadata extends com.coffer.auth.domain.TenantOwnedEntity {
    @Column(name = "model_snapshot_id", length = 36)
    private String modelSnapshotId;

    /** 自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 文件名称，上传时必填。TEXT 类型以支持中文全文索引（ngram 分词）。 */
    @NotBlank
    @Column(name = "file_name", nullable = false, columnDefinition = "TEXT")
    private String fileName;

    /** 文件大小，单位：字节。 */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** 文件扩展名（不含点号），如 pdf、docx、txt。 */
    @Column(name = "file_type", length = 50)
    private String fileType;

    /** MinIO 对象存储中的对象键值（相对路径）。 */
    @Column(name = "storage_path", length = 500)
    private String storagePath;

    /** 上传时间，由 Hibernate 在插入时自动填充。 */
    @CreationTimestamp
    @Column(name = "upload_time", updatable = false)
    private LocalDateTime uploadTime;

    /** AI 生成的摘要内容。TEXT 类型以支持中文全文索引（ngram 分词）。 */
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    /** Redis 向量索引成功写入的时间；NULL 表示待后台补建。 */
    @Column(name = "vector_indexed_at")
    private LocalDateTime vectorIndexedAt;

    /** Generation in which the current vector index was successfully built. */
    @Column(name = "vector_index_generation", length = 64)
    private String vectorIndexGeneration;

    /** 处理状态，以字符串形式持久化。 */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 20)
    private FileStatus status = FileStatus.PENDING;

    /** 异步任务 ID，关联异步任务表，供前端轮询任务进度。 */
    @Column(name = "task_id", length = 64)
    private String taskId;

    /** 文件分类（受控词表），AI 打标时单独输出的单一分类字段，默认 OTHER。归档时决定目标目录 slug。 */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "category", length = 30)
    private CategoryType category = CategoryType.OTHER;

    /** 是否已归档（物理移动到分类目录）。标签确认触发归档成功置 true，用于幂等防重复移动。 */
    @Builder.Default
    @Column(name = "archived", nullable = false)
    private boolean archived = false;

    /** 文件治理版本号；正式名称、分类、路径或对象内容变化时递增。 */
    @Builder.Default
    @Column(name = "revision", nullable = false)
    private Long revision = 0L;

    /** 对象内容指纹（通常为 MinIO ETag），用于预览和执行阶段的乐观校验。 */
    @Column(name = "content_etag", length = 255)
    private String contentEtag;

    /**
     * 将状态置为「处理中」，用于 Agent 开始分析时调用。
     */
    public void markAsProcessing() {
        this.status = FileStatus.PROCESSING;
    }

    /**
     * 将状态置为「已完成」，并写入 AI 生成的摘要，用于分析成功后调用。
     *
     * @param summary AI 生成的摘要内容
     */
    public void markAsCompleted(String summary) {
        this.revision = (this.revision == null ? 0L : this.revision) + 1;
        this.status = FileStatus.COMPLETED;
        this.summary = summary;
    }

    /**
     * 将状态置为「处理失败」，用于分析异常时调用。
     */
    public void markAsFailed() {
        this.status = FileStatus.FAILED;
    }
}
