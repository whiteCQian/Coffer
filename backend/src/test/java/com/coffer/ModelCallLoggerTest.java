package com.coffer;

import com.coffer.entity.ModelCallLog;
import com.coffer.model.provider.ChatProvider;
import com.coffer.repository.ModelCallLogRepository;
import com.coffer.service.ModelCallService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 模型调用切面集成测试：验证 {@code @LogModelCall} 切面拦截 + {@code @Async} 异步落库全链路。
 *
 * <p>OpenAiChatModel 以 {@link MockitoBean} 注入（避免真实 API 调用，保证单测确定性，
 * 真实调用留到启动实测验证），按 sessionId + status 过滤日志，避免与其他测试共享上下文时干扰。
 */
@SpringBootTest
class ModelCallLoggerTest {

    @Autowired
    private ModelCallService modelCallService;

    @Autowired
    private ModelCallLogRepository repository;

    @MockitoBean
    private ChatProvider chatProvider;

    @Test
    void aspectInterceptsAndPersists() throws Exception {
        when(chatProvider.chat(anyString())).thenReturn("你好，Coffer 智能文件管家");

        String reply = modelCallService.callModel("Hello", "s-aspect-test");
        assertThat(reply).isEqualTo("你好，Coffer 智能文件管家");

        // 等待 @Async 异步落库（轮询最多 15 秒）
        ModelCallLog saved = null;
        for (int i = 0; i < 30; i++) {
            List<ModelCallLog> rows = repository.findBySessionIdOrderByCallTimeDesc("s-aspect-test");
            saved = rows.stream()
                    .filter(log -> "SUCCESS".equals(log.getStatus()))
                    .findFirst()
                    .orElse(null);
            if (saved != null) {
                break;
            }
            Thread.sleep(500);
        }

        assertThat(saved).as("模型调用日志应已异步落库").isNotNull();
        assertThat(saved.getStatus()).isEqualTo("SUCCESS");
        assertThat(saved.getUserMessage()).isEqualTo("Hello");
        assertThat(saved.getCallTime()).isNotNull();
        assertThat(saved.getResponseTimeMs()).isGreaterThanOrEqualTo(0);
    }
}
