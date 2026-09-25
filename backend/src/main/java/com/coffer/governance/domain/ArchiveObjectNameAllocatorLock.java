package com.coffer.governance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 归档对象名分配器的数据库锁行。
 *
 * <p>固定锁住 id=1 的行，避免多个请求同时为同一天同名文件分配相同序号。</p>
 */
@Entity
@Table(name = "archive_object_name_allocator_lock")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArchiveObjectNameAllocatorLock {

    @Id
    private Long id;

    @Column(name = "lock_name", nullable = false, unique = true, length = 64)
    private String lockName;
}
