package com.coffer.file.infrastructure.persistence;

import com.coffer.file.domain.CategoryType;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

/**
 * 文件元数据 Repository。
 */
@Repository
public interface FileMetadataRepository extends com.coffer.auth.infrastructure.OwnedRepository<FileMetadata, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM FileMetadata f WHERE f.id = :id")
    Optional<FileMetadata> findByIdForUpdate(@Param("id") Long id);

    /**
     * 按任务 ID 查询文件元数据（文件经 task_id 列与异步任务关联）。
     *
     * @param taskId 异步任务 ID（AsyncTask.taskId）
     * @return 关联的文件元数据（可能为空）
     */
    Optional<FileMetadata> findByTaskId(String taskId);

    /** 已完成但尚未成功写入向量索引的文件，供后台补偿任务分批处理。 */
    Page<FileMetadata> findByStatusAndVectorIndexedAtIsNull(FileStatus status, Pageable pageable);

    Page<FileMetadata> findByStatus(FileStatus status, Pageable pageable);
    long countByStatus(FileStatus status);

    /**
     * 按上传时间倒序取最近上传的文件（时间相同按自增 ID 倒序稳定），供「最近上传」队列使用。
     *
     * <p>「最近上传 = 最近 N 个上传文件（先进先出窗口）」由本查询按上传时间实时推导，
     * 不额外建表/建 Redis 列表存储队列：新文件上传后自动顶替最旧者——第 N+1 个新文件入列时，
     * 最早的第 1 个自然移出窗口；文件被删除也即时生效。uploadTime 与自增 ID 均随「录入顺序」
     * 单调递增，故 {@code uploadTime DESC, id DESC} 即等于按时间顺序录入、先进先出。
     * 调用方以 {@code PageRequest.of(0, N)} 传入，取第一页前 N 条即为最近 N 个上传。
     *
     * @param pageable 分页参数（只取第一页，pageSize = 队列长度）
     * @return 按上传时间倒序的文件列表（同时间按 ID 倒序）
     */
    @Query("SELECT f FROM FileMetadata f ORDER BY f.uploadTime DESC, f.id DESC")
    List<FileMetadata> findRecentFiles(Pageable pageable);

    /**
     * 按「文件名 或 AI 摘要」模糊查询（忽略大小写），供 Agent 搜索工具在全文索引不可用/未命中时降级调用。
     *
     * <p>检索维度与 MySQL FULLTEXT 的 {@code MATCH(file_name, summary)} 对齐：既匹配文件名，也匹配
     * AI 摘要（含 Qwen-VL 图片描述），保证图片/文本文件都能按内容被 Agent 找到；其余两维（文件名 LIKE、
     * 已确认标签）的去重合并逻辑不变。
     *
     * @param keyword 搜索关键词
     * @return 文件名或摘要命中的文件元数据列表
     */
    @Query("SELECT f FROM FileMetadata f WHERE LOWER(f.fileName) LIKE LOWER(CONCAT('%', :keyword, '%')) "
            + "OR LOWER(f.summary) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<FileMetadata> findByFileNameOrSummaryContainingIgnoreCase(@Param("keyword") String keyword);

    /**
     * MySQL 全文检索：对 fileName 与 summary 字段执行 {@code MATCH...AGAINST}，
     * 结果按相关性降序返回。依赖手动创建的 FULLTEXT 索引
     * {@code idx_file_search}（ngram 中文分词，见 docs/archive/sql/fulltext_search.sql）。
     *
     * <p>注意：此查询为 MySQL 专用语法，H2 测试环境执行会抛出异常，
     * 调用方须捕获异常并降级到 {@link #findByFileNameOrSummaryContainingIgnoreCase(String)}。
     *
     * <p>以 {@code REQUIRES_NEW} 独立事务执行：H2 等不支持 {@code MATCH...AGAINST} 的数据库
     * 抛异常时，仅回滚本方法的新事务，避免把外层事务（如对话 {@code ConversationService}
     * 的 {@code @Transactional}）标记为 rollback-only，导致调用方降级成功后提交时报
     * {@code UnexpectedRollbackException}。
     *
     * @param keyword 搜索关键词
     * @return 按相关性排序的匹配文件列表
     */
    @Query(value = "SELECT * FROM file_metadata WHERE owner_id = :ownerId "
            + "AND MATCH(file_name, summary) AGAINST(:keyword IN NATURAL LANGUAGE MODE)",
            nativeQuery = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    List<FileMetadata> fullTextSearch(@Param("keyword") String keyword, @Param("ownerId") Long ownerId);

    /**
     * 列表搜索：按文件名模糊匹配「或」未拒绝标签名模糊匹配（任一命中即返回），支持分页。
     *
     * <p>标签匹配统计 已确认（CONFIRMED）与 待确认（PENDING_CONFIRMATION）两类标签，
     * 已拒绝（REJECTED）不参与匹配——让 AI 刚打完标、尚未手动确认的文件也能被关键词命中；
     * 与 {@code FileSearchTool} 的标签搜索语义保持一致，保证全局搜索规则统一。
     * 使用 {@code LEFT JOIN} 保证未打标签的文件仍可按文件名命中；
     * {@code DISTINCT} 去重防止一文件命中多个标签时重复返回。
     *
     * @param keyword  搜索关键词
     * @param pageable 分页参数
     * @return 匹配的文件分页结果
     */
    @Query(value = "SELECT DISTINCT fm FROM FileMetadata fm " +
            "LEFT JOIN FileTagMapping ftm ON fm.id = ftm.fileId " +
            "LEFT JOIN Tag t ON ftm.tagId = t.id " +
            "WHERE LOWER(fm.fileName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(fm.summary) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR (LOWER(t.tagName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "AND ftm.confirmationStatus <> com.coffer.tag.domain.ConfirmationStatus.REJECTED)",
            countQuery = "SELECT COUNT(DISTINCT fm) FROM FileMetadata fm " +
                    "LEFT JOIN FileTagMapping ftm ON fm.id = ftm.fileId " +
                    "LEFT JOIN Tag t ON ftm.tagId = t.id " +
                    "WHERE LOWER(fm.fileName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                    "OR LOWER(fm.summary) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                    "OR (LOWER(t.tagName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                    "AND ftm.confirmationStatus <> com.coffer.tag.domain.ConfirmationStatus.REJECTED)")
    Page<FileMetadata> searchByFileNameOrTag(@Param("keyword") String keyword, Pageable pageable);

    /**
     * 组合搜索：关键词匹配文件名「且」标签匹配未拒绝标签名，支持分页。
     *
     * <p>两个参数均为可选：传空串（{@code ''}）表示不约束对应维度，二者都为空等价于查全部。
     * 标签匹配统计 已确认（CONFIRMED）与 待确认（PENDING_CONFIRMATION）标签，
     * 已拒绝（REJECTED）不参与匹配——与全局搜索规则一致。
     * 使用 {@code LEFT JOIN} 保证未打标签的文件仍可按文件名命中；{@code DISTINCT} 去重。
     *
     * @param keyword  文件名关键词，空串不约束
     * @param tag      标签名关键词（匹配已确认/待确认标签），空串不约束
     * @param pageable 分页参数
     * @return 匹配的文件分页结果
     */
    @Query(value = "SELECT DISTINCT fm FROM FileMetadata fm " +
            "LEFT JOIN FileTagMapping ftm ON fm.id = ftm.fileId " +
            "LEFT JOIN Tag t ON ftm.tagId = t.id " +
            "WHERE (:keyword = '' OR LOWER(fm.fileName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(fm.summary) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "AND (:tag = '' OR (LOWER(t.tagName) LIKE LOWER(CONCAT('%', :tag, '%')) " +
            "AND ftm.confirmationStatus <> com.coffer.tag.domain.ConfirmationStatus.REJECTED))",
            countQuery = "SELECT COUNT(DISTINCT fm) FROM FileMetadata fm " +
                    "LEFT JOIN FileTagMapping ftm ON fm.id = ftm.fileId " +
                    "LEFT JOIN Tag t ON ftm.tagId = t.id " +
                    "WHERE (:keyword = '' OR LOWER(fm.fileName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(fm.summary) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
                    "AND (:tag = '' OR (LOWER(t.tagName) LIKE LOWER(CONCAT('%', :tag, '%')) " +
                    "AND ftm.confirmationStatus <> com.coffer.tag.domain.ConfirmationStatus.REJECTED))")
    Page<FileMetadata> searchFiles(@Param("keyword") String keyword, @Param("tag") String tag, Pageable pageable);

    /**
     * 按分类分页查询全部文件（无关键词），供「仅按分类过滤」的列表场景。
     *
     * @param category 分类枚举
     * @param pageable 分页参数
     * @return 该分类下的文件分页结果
     */
    Page<FileMetadata> findByCategory(CategoryType category, Pageable pageable);

    /**
     * 组合搜索（列表页）：关键词匹配 文件名 或 AI 摘要 或 未拒绝标签名（OR），
     * 同时可按分类过滤（可选）。category 传 null 不约束分类。
     *
     * <p>标签匹配统计 已确认（CONFIRMED）与 待确认（PENDING_CONFIRMATION）标签，
     * 已拒绝（REJECTED）不参与匹配，与全局搜索规则一致；
     * {@code LEFT JOIN} 保证未打标签的文件仍可按文件名/摘要命中；{@code DISTINCT} 去重。
     *
     * @param keyword  关键词，空串不约束
     * @param category 分类过滤，null 不约束
     * @param pageable 分页参数
     * @return 匹配的文件分页结果
     */
    @Query(value = "SELECT DISTINCT fm FROM FileMetadata fm " +
            "LEFT JOIN FileTagMapping ftm ON fm.id = ftm.fileId " +
            "LEFT JOIN Tag t ON ftm.tagId = t.id " +
            "WHERE (:keyword = '' OR LOWER(fm.fileName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(fm.summary) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR (LOWER(t.tagName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "AND ftm.confirmationStatus <> com.coffer.tag.domain.ConfirmationStatus.REJECTED)) " +
            "AND (:category IS NULL OR fm.category = :category)",
            countQuery = "SELECT COUNT(DISTINCT fm) FROM FileMetadata fm " +
                    "LEFT JOIN FileTagMapping ftm ON fm.id = ftm.fileId " +
                    "LEFT JOIN Tag t ON ftm.tagId = t.id " +
                    "WHERE (:keyword = '' OR LOWER(fm.fileName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                    "OR LOWER(fm.summary) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                    "OR (LOWER(t.tagName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                    "AND ftm.confirmationStatus <> com.coffer.tag.domain.ConfirmationStatus.REJECTED)) " +
                    "AND (:category IS NULL OR fm.category = :category)")
    Page<FileMetadata> searchByKeywordAndCategory(@Param("keyword") String keyword,
                                                  @Param("category") CategoryType category, Pageable pageable);

    /**
     * 查询待确认标签文件：处理已完成（COMPLETED）且存在至少一条待确认（PENDING_CONFIRMATION）
     * 标签关联的文件，按上传时间倒序，供首页「待确认」面板展示。
     *
     * <p>用 {@code JOIN}（内连接）保证只统计已打标签的文件；{@code DISTINCT} 去重——
     * 同一文件命中多条待确认标签关联时只返回一次。与 {@code TaskOverviewService}
     * 的组合待确认状态口径一致：只要还有一条待确认即入列，不受同文件其他已确认/已拒绝标签影响。
     *
     * @return 待确认标签文件列表（上传时间倒序）
     */
    @Query("SELECT DISTINCT fm FROM FileMetadata fm " +
            "JOIN FileTagMapping ftm ON fm.id = ftm.fileId " +
            "WHERE fm.status = com.coffer.file.domain.FileStatus.COMPLETED " +
            "AND ftm.confirmationStatus = com.coffer.tag.domain.ConfirmationStatus.PENDING_CONFIRMATION " +
            "ORDER BY fm.uploadTime DESC")
    List<FileMetadata> findPendingConfirmFiles();

    /**
     * 按分类分组统计文件数量，仅返回数量大于 0 的分类，按数量倒序。
     *
     * <p>供「全部文件」页分类徽标等场景展示。category 非空（默认 OTHER）时均纳入统计。
     *
     * @return 分类计数投影列表（数量倒序）
     */
    @Query("SELECT f.category AS category, COUNT(f) AS count FROM FileMetadata f " +
            "WHERE f.category IS NOT NULL GROUP BY f.category " +
            "HAVING COUNT(f) > 0 ORDER BY COUNT(f) DESC")
    List<CategoryCount> countByCategory();

    /**
     * 分类计数投影接口：字段别名须与 {@link #countByCategory} 的投影别名一致
     * （category / count），Spring Data 按属性名匹配结果列。
     */
    interface CategoryCount {

        /** 分类枚举。 */
        CategoryType getCategory();

        /** 该分类下的文件数量。 */
        Long getCount();
    }
}
