package com.coffer;

import com.coffer.entity.ModelCallLog;
import com.coffer.model.provider.ChatProvider;
import com.coffer.repository.ModelCallLogRepository;
import com.coffer.service.RetryableModelService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重试降级集成测试：验证重试机制 + 切面重试日志 + 异步落库全链路。
 *
 * <p>OpenAiChatModel 以 {@link MockitoBean} 注入（避免真实 API），
 * 模拟连接异常触发超时归类，重试 2 次（共 3 次尝试）后降级；
 * 日志按 sessionId + status 过滤，避免与其他测试共享上下文时干扰。
 */
@SpringBootTest(properties = "coffer.model-log.enabled=true")
class RetryFlowTest extends com.coffer.auth.OwnerTestSupport {

    @Autowired
    private RetryableModelService retryableModelService;

    @Autowired
    private ModelCallLogRepository repository;

    @MockitoBean
    private ChatProvider chatProvider;

    @Test
    void retriesTwiceThenDegrades() throws Exception {
        java.time.LocalDateTime startedAt = java.time.LocalDateTime.now().minusSeconds(1);
        // 模拟 DeepSeek 连接异常（translateException 归类为 TimeoutException）
        when(chatProvider.chat(anyString())).thenThrow(new RuntimeException("Connection refused: connect"));

        String result = retryableModelService.callModelWithRetry("hi", "s1");

        // 共 3 次尝试，最终返回降级响应
        verify(chatProvider, times(3)).chat(anyString());
        assertThat(result).contains("超时");

        // 等待异步落库：期望 3 条 FAILED 记录，retryCount 依次为 0、1、2
        List<ModelCallLog> failed = null;
        for (int i = 0; i < 30; i++) {
            failed = repository.findAll().stream()
                    .filter(log -> log.getCallTime().isAfter(startedAt))
                    .filter(log -> "FAILED".equals(log.getStatus()))
                    .toList();
            if (failed.size() >= 3) {
                break;
            }
            Thread.sleep(500);
        }

        assertThat(failed).hasSize(3);
        assertThat(failed).allMatch(log -> "FAILED".equals(log.getStatus()));
        assertThat(failed.stream().map(ModelCallLog::getRetryCount).sorted()).containsExactly(0, 1, 2);
        assertThat(failed).allMatch(log -> log.getSessionId() == null
                && log.getUserMessage() == null && log.getAiResponse() == null);
        assertThat(failed).allMatch(log -> "MODEL_FAILURE".equals(log.getErrorMessage()));
    }
}
