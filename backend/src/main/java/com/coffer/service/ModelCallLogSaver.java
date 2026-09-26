package com.coffer.service;

import com.coffer.entity.ModelCallLog;
import com.coffer.repository.ModelCallLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 模型调用日志异步持久化器。
 *
 * <p>独立 Bean 持有 {@link @Async} 方法，确保 Spring 代理生效；
 * 异步保存不影响主流程响应速度，保存失败仅打印错误日志，不向上抛出异常。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelCallLogSaver {

    private final ModelCallLogRepository modelCallLogRepository;

    /**
     * 异步保存模型调用日志，失败时仅记录错误，不抛出异常。
     *
     * @param modelCallLog 模型调用日志
     */
    @Async("modelLogExecutor")
    public void saveAsync(ModelCallLog modelCallLog) {
        try {
            modelCallLog.setUserMessage(null);
            modelCallLog.setAiResponse(null);
            modelCallLog.setSessionId(null);
            modelCallLog.setModelName(null);
            if (modelCallLog.getErrorMessage() != null) modelCallLog.setErrorMessage("MODEL_FAILURE");
            modelCallLogRepository.save(modelCallLog);
        } catch (Exception e) {
            log.error("模型调用诊断记录保存失败，异常类型={}", e.getClass().getSimpleName());
        }
    }
}
