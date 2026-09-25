package com.coffer.task;

import com.coffer.repository.ModelCallLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 模型调用日志定时清理任务：每日凌晨 3 点删除 30 天前的调用日志，
 * 控制日志表体积，保留近一个月的调用记录供成本统计与监控。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelCallLogCleanupTask {

    /** 日志保留天数。 */
    private static final int RETENTION_DAYS = 30;

    private final ModelCallLogRepository modelCallLogRepository;

    /**
     * 清理 30 天前的模型调用日志。
     *
     * <p>cron {@code 0 0 3 * * *}：每天凌晨 3 点整执行。删除由
     * {@code ModelCallLogRepository#deleteByCallTimeBefore} 完成（方法级 @Transactional）。
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanExpiredLogs() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        try {
            modelCallLogRepository.deleteByCallTimeBefore(cutoff);
            log.info("模型调用日志清理完成，已删除 {} 天前（{}）的日志", RETENTION_DAYS, cutoff);
        } catch (Exception e) {
            log.error("模型调用日志清理失败 cutoff={}: {}", cutoff, e.getMessage(), e);
        }
    }
}
