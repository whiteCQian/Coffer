package com.coffer.exception;

import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.model.runtime.ModelModeNotReadyException;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ModelModeErrorResponseTest {
    @RestController
    static class Probe {
        @GetMapping("/mode-not-ready") public void notReady() {
            throw ModelModeNotReadyException.currentNotValidated(GovernanceRunMode.LOCAL);
        }
        @GetMapping("/generic-invalid") public void invalid() {
            throw new IllegalArgumentException("secret-key-and-private-file-content");
        }
        @GetMapping("/invalid-model-path") public void invalidModelPath() {
            com.coffer.model.runtime.ModelRuntimeEndpointConfigurationService.validateAddress(GovernanceRunMode.API,
                    "https://maas.qianwenaiapi.com/api/v1", "maas.qianwenaiapi.com", "", "");
        }
        @GetMapping("/invalid-model-host") public void invalidModelHost() {
            com.coffer.model.runtime.ModelRuntimeEndpointConfigurationService.validateAddress(GovernanceRunMode.API,
                    "https://secret-key-and-private-file-content.example/v1", "maas.qianwenaiapi.com", "", "");
        }
    }

    @Test
    void actionableModeErrorsAreExposedWhileArbitraryExceptionDetailsStayPrivate() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/mode-not-ready")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("本地模式（LOCAL）")))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("测试当前模式")));
        mvc.perform(get("/generic-invalid")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("请求参数不符合要求，请检查输入后重试"));
        mvc.perform(get("/invalid-model-path")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("OpenAI 兼容 Base URL")));
        mvc.perform(get("/invalid-model-host")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("域名未在服务器允许列表")))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-key"))));
    }
}
