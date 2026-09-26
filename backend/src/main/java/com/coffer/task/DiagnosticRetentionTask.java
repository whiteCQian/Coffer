package com.coffer.task;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/** Content-free maintenance also covers disabled and retired accounts. No rows are read or returned. */
@Component @RequiredArgsConstructor
public class DiagnosticRetentionTask {
    private final JdbcTemplate jdbc;

    @Scheduled(cron = "0 15 3 * * *") @Transactional
    public void purgeExpired() {
        jdbc.update("delete from model_call_log where call_time < ?", Timestamp.valueOf(LocalDateTime.now().minusDays(30)));
    }
}
