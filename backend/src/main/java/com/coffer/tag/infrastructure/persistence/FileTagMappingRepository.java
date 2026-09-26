package com.coffer.tag.infrastructure.persistence;

import com.coffer.tag.domain.ConfirmationStatus;
import com.coffer.tag.domain.FileTagMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 文件-标签关联 Repository。
 */
@Repository
public interface FileTagMappingRepository extends com.coffer.auth.infrastructure.OwnedRepository<FileTagMapping, Long> {

    /**
     * 获取某个文件的所有标签关联。
     *
     * @param fileId 文件 ID
     * @return 关联记录列表
     */
    List<FileTagMapping> findByFileId(Long fileId);

    /**
     * Remove every tag association owned by one file in a single database statement.
     * Used when a confirmed governance preview defines the complete replacement set.
     *
     * @param fileId file whose tag set is being replaced
     * @return number of removed associations
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM file_tag_mapping WHERE file_id = :fileId AND owner_id = :ownerId", nativeQuery = true)
    int deleteByFileId(@Param("fileId") Long fileId, @Param("ownerId") Long ownerId);

    /**
     * 获取某个文件下指定确认状态的关联记录。
     *
     * @param fileId 文件 ID
     * @param status 确认状态
     * @return 关联记录列表
     */
    List<FileTagMapping> findByFileIdAndConfirmationStatus(Long fileId, ConfirmationStatus status);

    /**
     * 判断文件与标签的关联是否已存在，避免重复关联。
     *
     * @param fileId 文件 ID
     * @param tagId  标签 ID
     * @return 存在返回 true，否则 false
     */
    boolean existsByFileIdAndTagId(Long fileId, Long tagId);

    /**
     * 按文件与标签 ID 查询关联记录（唯一约束 (file_id, tag_id) 保证至多一条）。
     *
     * @param fileId 文件 ID
     * @param tagId  标签 ID
     * @return 关联记录（可能为空）
     */
    Optional<FileTagMapping> findByFileIdAndTagId(Long fileId, Long tagId);

    /**
     * 按标签名模糊匹配且确认状态为指定值时，查询关联的文件 ID 列表。
     *
     * <p>以逻辑外键显式 JOIN 两表（FileTagMapping ↔ Tag），{@code DISTINCT} 去重防止
     * 同一文件命中多个标签时重复返回。仅返回 fileId 列，调用方再经
     * {@code FileMetadataRepository.findAllById} 装载实体，避免返回非实体列。
     *
     * @param keyword 标签名关键词
     * @param status  标签确认状态（搜索场景应传 {@code CONFIRMED}，未确认/已拒绝不参与匹配）
     * @return 满足条件的文件 ID 列表
     */
    @Query("SELECT DISTINCT ftm.fileId FROM FileTagMapping ftm JOIN Tag t ON ftm.tagId = t.id " +
            "WHERE LOWER(t.tagName) LIKE LOWER(CONCAT('%', :keyword, '%')) AND ftm.confirmationStatus = :status")
    List<Long> findFileIdsByTagNameAndStatus(@Param("keyword") String keyword, @Param("status") ConfirmationStatus status);

    /**
     * 批量查询多个文件下的全部标签关联（含 PENDING_CONFIRMATION/CONFIRMED/REJECTED），
     * 供文件列表一次性加载当前页所有文件的标签，避免循环中逐条查询造成 N+1。
     *
     * @param fileIds 文件 ID 集合
     * @return 关联记录列表
     */
    List<FileTagMapping> findByFileIdIn(Collection<Long> fileIds);

    /**
     * 标签候选池：统计每个标签名被多少<b>个文件</b>确认采用，按覆盖文件数倒序。
     *
     * <p>仅统计 {@code CONFIRMED} 关联（待确认/已拒绝不进入候选池），对每个标签名做
     * {@code COUNT(DISTINCT fileId)} 去重——同一文件不会因同标签多条关联被重复计数
     * （(file_id, tag_id) 唯一约束下本不会发生，去重为语义兜底）。标签名全局唯一，
     * 故 GROUP BY tagName 等价于按标签聚合。
     *
     * <p>以显式 JOIN 关联 Tag 表取标签名（逻辑外键，与 {@link #findFileIdsByTagNameAndStatus} 同款写法）。
     *
     * @return 标签候选投影列表（name=标签名，cnt=覆盖去重文件数），按 cnt 倒序
     */
    @Query("SELECT t.tagName AS name, COUNT(DISTINCT ftm.fileId) AS cnt " +
            "FROM FileTagMapping ftm JOIN Tag t ON ftm.tagId = t.id " +
            "WHERE ftm.confirmationStatus = com.coffer.tag.domain.ConfirmationStatus.CONFIRMED " +
            "GROUP BY t.tagName ORDER BY cnt DESC")
    List<TagCandidate> findTagCandidates();

    /**
     * 标签候选投影：别名 name/cnt 对应查询中 {@code AS name}/{@code AS cnt}。
     */
    interface TagCandidate {

        /** 标签名。 */
        String getName();

        /** 确认采用该标签的去重文件数。 */
        Long getCnt();
    }
}
