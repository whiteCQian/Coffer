package com.coffer.operations;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.coffer.privacy.PrivacyLogConverter;
import com.coffer.task.DiagnosticRetentionTask;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.sql.Timestamp;
import static org.assertj.core.api.Assertions.*;

class DiagnosticPrivacyTest {
    static final String MARKER = "R26_PRIVATE_CONTENT_SECRET_FILE_PATH";
    @Test void encoderOmitsArgumentsThrowableMdcAndUnregisteredMessages() {
        var context = new LoggerContext();
        try {
            context.putObject(ch.qos.logback.core.CoreConstants.PATTERN_RULE_REGISTRY,
                    java.util.Map.of("privacyEvent", PrivacyLogConverter.class.getName()));
            var layout = new PatternLayout(); layout.setContext(context); layout.setPattern("%level %logger %privacyEvent%n%nopex"); layout.start();
            for (String template : java.util.List.of("参数校验失败", "模型调用异常，类型={}", MARKER, "动态文件 {}")) {
                var event = new LoggingEvent(getClass().getName(), context.getLogger(getClass()), Level.ERROR, template, new IllegalStateException(MARKER), new Object[]{MARKER});
                event.setMDCPropertyMap(java.util.Map.of("owner", MARKER));
                assertThat(layout.doLayout(event)).doesNotContain(MARKER, "IllegalStateException", "动态文件").containsAnyOf("INVALID_REQUEST", "MODEL_FAILED", "EVENT_RECORDED");
            }
        } finally { context.stop(); }
    }
    @Test void retentionRedactsAllOwnersIncludingOwnerlessAndKeepsActiveAlerts() {
        var db = new OperationsDatabase(); long owner = db.owner(false);
        for (Long ownerId : new Long[]{null, owner}) {
            db.jdbc.update("INSERT INTO model_call_log(owner_id,user_message,ai_response,model_name,session_id,error_message,call_time) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)", ownerId, MARKER, MARKER, MARKER, MARKER, MARKER);
        }
        db.jdbc.update("INSERT INTO model_call_log(user_message,call_time) VALUES(?,?)", MARKER, Timestamp.valueOf(LocalDateTime.now().minusDays(31)));
        db.jdbc.update("INSERT INTO account_audit_log(actor_user_id,actor_username,action,created_at) VALUES(1,'admin','RESET',?)", Timestamp.valueOf(LocalDateTime.now().minusDays(181)));
        for (boolean active : new boolean[]{true, false}) db.jdbc.update("INSERT INTO runtime_alert(code,severity,first_seen,last_seen,resolved_at) VALUES('TASK_BACKLOG','WARNING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)", active ? null : Timestamp.valueOf(LocalDateTime.now().minusDays(91)));
        db.jdbc.update("INSERT INTO secret_rotation_event(key_id,verified_count,rewritten_count,completed_at) VALUES('abcdef',1,1,?)", Timestamp.valueOf(LocalDateTime.now().minusDays(181)));
        var task = new DiagnosticRetentionTask(db.jdbc); task.redactOnStartup();
        assertThat(db.jdbc.queryForList("SELECT user_message,ai_response,model_name,session_id,error_message FROM model_call_log").toString()).doesNotContain(MARKER);
        task.purgeExpired();
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM model_call_log", Long.class)).isEqualTo(2);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM account_audit_log", Long.class)).isZero();
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM secret_rotation_event", Long.class)).isZero();
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM runtime_alert", Long.class)).isEqualTo(1);
    }
}
