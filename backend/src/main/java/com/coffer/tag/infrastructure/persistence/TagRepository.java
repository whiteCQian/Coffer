package com.coffer.tag.infrastructure.persistence;

import com.coffer.tag.domain.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 标签 Repository。
 */
@Repository
public interface TagRepository extends JpaRepository<Tag, Long> {

    /**
     * 按标签名精确查找。
     *
     * @param tagName 标签名
     * @return 匹配的标签（可能为空）
     */
    Optional<Tag> findByTagName(String tagName);
}
