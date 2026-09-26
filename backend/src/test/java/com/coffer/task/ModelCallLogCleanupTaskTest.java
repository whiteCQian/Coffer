package com.coffer.task;

import com.coffer.entity.ModelCallLog;
import com.coffer.repository.ModelCallLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型调用日志清理任务集成测试：验证只删除 30 天前的日志，保留近期的日志。
 *
 * <p>不使用 {@code @Transactional}（否则删除会随测试回滚无法验证），
 * 由 {@link AfterEach} 清空表数据避免污染其它测试。
 */
@SpringBootTest
class ModelCallLogCleanupTaskTest extends com.coffer.auth.OwnerTestSupport {

    @Autowired
    private ModelCallLogCleanupTask task;

    @Autowired
    private ModelCallLogRepository repository;

    @AfterEach
    void cleanup() {
        repository.deleteAll();
    }

    @Test
    void deletesLogsOlderThan30Days() {
        LocalDateTime old = LocalDateTime.now().minusDays(31);
        LocalDateTime recent = LocalDateTime.now().minusDays(1);
        ModelCallLog oldLog = repository.save(
                ModelCallLog.builder().callTime(old).modelName("m").status("SUCCESS").build());
        ModelCallLog recentLog = repository.save(
                ModelCallLog.builder().callTime(recent).modelName("m").status("SUCCESS").build());

        task.cleanExpiredLogs();

        // 用 id 定位避免 H2 时间精度截断导致的时间戳比对失败
        assertThat(repository.findById(oldLog.getId())).isEmpty();
        assertThat(repository.findById(recentLog.getId())).isPresent();
    }

    @Test
    void retainsRecentLogsAfterRedactingLegacyContent() {
        LocalDateTime recent = LocalDateTime.now().minusDays(10);
        ModelCallLog recentLog = repository.save(
                ModelCallLog.builder().callTime(recent).modelName("m").status("SUCCESS")
                        .sessionId("private-session")
                        .userMessage("private prompt")
                        .aiResponse("private response")
                        .errorMessage("private provider detail")
                        .build());

        task.cleanExpiredLogs();

        ModelCallLog retained = repository.findById(recentLog.getId()).orElseThrow();
        assertThat(retained.getModelName()).isNull();
        assertThat(retained.getSessionId()).isNull();
        assertThat(retained.getUserMessage()).isNull();
        assertThat(retained.getAiResponse()).isNull();
        assertThat(retained.getErrorMessage()).isNull();
    }
}
