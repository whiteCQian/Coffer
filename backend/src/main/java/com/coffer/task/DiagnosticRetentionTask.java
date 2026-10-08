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
        jdbc.update("update model_call_log set user_message=NULL, ai_response=NULL, session_id=NULL, model_name=NULL, error_message=NULL");
        jdbc.update("delete from model_call_log where call_time < ?", Timestamp.valueOf(LocalDateTime.now().minusDays(30)));
        jdbc.update("delete from account_audit_log where created_at < ?", Timestamp.valueOf(LocalDateTime.now().minusDays(180)));
        jdbc.update("delete from runtime_alert where resolved_at < ?", Timestamp.valueOf(LocalDateTime.now().minusDays(90)));
        jdbc.update("delete from secret_rotation_event where completed_at < ?", Timestamp.valueOf(LocalDateTime.now().minusDays(180)));
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void redactOnStartup() {
        jdbc.update("update model_call_log set user_message=NULL, ai_response=NULL, session_id=NULL, model_name=NULL, error_message=NULL");
    }
}
