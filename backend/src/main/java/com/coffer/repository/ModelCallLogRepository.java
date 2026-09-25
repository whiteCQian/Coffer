package com.coffer.repository;

import com.coffer.entity.ModelCallLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 模型调用日志 Repository。
 */
@Repository
public interface ModelCallLogRepository extends JpaRepository<ModelCallLog, Long> {

    /**
     * 按会话查询调用日志，按调用时间倒序排列（最新在前）。
     *
     * @param sessionId 会话标识
     * @return 调用日志列表
     */
    List<ModelCallLog> findBySessionIdOrderByCallTimeDesc(String sessionId);

    /**
     * 删除指定时间之前的日志（定期清理历史数据）。
     * <p>派生删除查询需要事务，方法级 {@link @Transactional} 保证在事务内执行。
     *
     * @param dateTime 时间界限（早于此时间的日志将被删除）
     */
    @Transactional
    void deleteByCallTimeBefore(LocalDateTime dateTime);
}
