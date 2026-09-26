package com.coffer.tag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文件-标签关联实体，承载 AI 自动打标与用户人工确认的结果。
 *
 * <p>以逻辑外键（{@code fileId}/{@code tagId} 两个 Long 字段）而非 JPA 对象关联，
 * 由应用层保证一致性；复合唯一约束 {@code (file_id, tag_id)} 确保
 * 同一文件不会重复关联同一标签。
 */
@Entity
@Table(name = "file_tag_mapping",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_file_tag_mapping_file_tag", columnNames = {"owner_id", "file_id", "tag_id"})
        },
        indexes = {
                // 说明：复合唯一约束 (file_id, tag_id) 的最左前缀已能支撑按 file_id 查询，
                // 此处 file_id 单列索引技术上冗余，按需求显式保留；tag_id 单列索引则必须，
                // 用于「按标签反向查文件」。
                @Index(name = "idx_file_tag_mapping_file_id", columnList = "file_id"),
                @Index(name = "idx_file_tag_mapping_tag_id", columnList = "tag_id")
        })
@Data
@lombok.EqualsAndHashCode(callSuper = false)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileTagMapping extends com.coffer.auth.domain.TenantOwnedEntity {

    /** 自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 文件 ID（逻辑外键，关联 file_metadata.id）。 */
    @Column(name = "file_id", nullable = false)
    private Long fileId;

    /** 标签 ID（逻辑外键，关联 tag.id）。 */
    @Column(name = "tag_id", nullable = false)
    private Long tagId;

    /** 确认状态，以字符串形式持久化。 */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "confirmation_status", nullable = false, length = 20)
    private ConfirmationStatus confirmationStatus = ConfirmationStatus.PENDING_CONFIRMATION;

    /** 确认时间，用户确认或拒绝时填充。 */
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** 确认备注，用户拒绝时填写的修正意见。 */
    @Column(name = "confirmation_note", length = 500)
    private String confirmationNote;

    /**
     * 确认该标签：置状态为 CONFIRMED 并记录确认时间。
     */
    public void confirm() {
        this.confirmationStatus = ConfirmationStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
    }

    /**
     * 拒绝该标签：置状态为 REJECTED、记录确认时间并保存修正意见。
     *
     * @param note 用户填写的修正意见
     */
    public void reject(String note) {
        this.confirmationStatus = ConfirmationStatus.REJECTED;
        this.confirmedAt = LocalDateTime.now();
        this.confirmationNote = note;
    }
}
