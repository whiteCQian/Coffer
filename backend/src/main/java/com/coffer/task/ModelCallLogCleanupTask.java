package com.coffer.task;

import com.coffer.repository.ModelCallLogRepository;
import com.coffer.auth.service.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 模型调用诊断数据定时清理任务：每日凌晨 3 点擦除历史正文及原始异常信息，
 * 并删除 30 天前的聚合诊断记录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelCallLogCleanupTask {

    /** 日志保留天数。 */
    private static final int RETENTION_DAYS = 30;

    private final ModelCallLogRepository modelCallLogRepository;

    /**
     * 擦除旧版日志中的内容字段，并清理 30 天前的诊断记录。
     *
     * <p>cron {@code 0 0 3 * * *}：每天凌晨 3 点整执行。删除由
     * {@code ModelCallLogRepository#deleteByCallTimeBefore} 完成（方法级 @Transactional）。
     */
    @com.coffer.auth.service.OwnerScheduled
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanExpiredLogs() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        try {
            int redacted = modelCallLogRepository.redactLegacyContent(TenantContext.requireOwnerId());
            modelCallLogRepository.deleteByCallTimeBefore(cutoff);
            log.info("模型调用诊断数据清理完成，已擦除 {} 条历史正文字段，并删除 {} 天前（{}）的记录",
                    redacted, RETENTION_DAYS, cutoff);
        } catch (Exception e) {
            log.error("模型调用诊断数据清理失败，异常类型={}", e.getClass().getSimpleName());
        }
    }
}
