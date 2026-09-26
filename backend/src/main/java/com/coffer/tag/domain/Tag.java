package com.coffer.tag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 标签实体，用于 AI 自动分类与用户检索。
 *
 * <p>标签名全局唯一，通过 {@code category} 区分大类
 * （如「项目名称」「文档类型」「日期」「人员」等），便于按分类筛选与展示。
 *
 * <p>表名 {@code tag}，标签名列 {@code tag_name} 建唯一索引。
 */
@Entity
@Table(name = "tag", uniqueConstraints = @jakarta.persistence.UniqueConstraint(
        name = "uk_tag_owner_name", columnNames = {"owner_id", "tag_name"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
// 本实体无父类，callSuper 参数无实际影响（@Data 已隐含 @EqualsAndHashCode 且默认 callSuper=false）；
// 显式声明仅为表达意图，若未来引入带字段的基类（如 BaseEntity）需改为 callSuper=true
@EqualsAndHashCode(callSuper = false)
public class Tag extends com.coffer.auth.domain.TenantOwnedEntity {

    /** 自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 标签名，全局唯一。 */
    @NotBlank
    @Column(name = "tag_name", nullable = false, length = 255)
    private String tagName;

    /** 标签分类，如「项目名称」「文档类型」「日期」「人员」等。 */
    @Column(name = "category", length = 50)
    private String category;

    /** 创建时间，由 Hibernate 在插入时自动填充。 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
