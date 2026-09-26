package com.coffer.repository;

import com.coffer.entity.ModelCallLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 模型调用日志 Repository。
 */
@Repository
public interface ModelCallLogRepository extends com.coffer.auth.infrastructure.OwnedRepository<ModelCallLog, Long> {

    /** Erase legacy prompt, response, session, and raw error values from retained diagnostics. */
    @Modifying
    @Transactional
    @Query("update ModelCallLog log set log.userMessage = null, log.aiResponse = null, "
            + "log.sessionId = null, log.modelName = null, log.errorMessage = null where log.ownerId = :ownerId")
    int redactLegacyContent(@org.springframework.data.repository.query.Param("ownerId") Long ownerId);

    /**
     * 删除指定时间之前的日志（定期清理历史数据）。
     * <p>派生删除查询需要事务，方法级 {@link @Transactional} 保证在事务内执行。
     *
     * @param dateTime 时间界限（早于此时间的日志将被删除）
     */
    @Transactional
    void deleteByCallTimeBefore(LocalDateTime dateTime);
}
