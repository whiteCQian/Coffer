package com.coffer.controller;

import com.coffer.entity.ModelProvider;
import com.coffer.service.ModelCredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ModelCredentialControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ModelCredentialService credentialService;

    @Test
    void returnsConfigurationStatusWithoutExposingKeys() throws Exception {
        when(credentialService.status()).thenReturn(Map.of(
                ModelProvider.DEEPSEEK, true,
                ModelProvider.QWEN_VL, false));
        when(credentialService.getMaskedApiKey(ModelProvider.DEEPSEEK)).thenReturn("sk-1234");
        when(credentialService.getMaskedApiKey(ModelProvider.QWEN_VL)).thenReturn("");

        mockMvc.perform(get("/api/settings/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.configured.DEEPSEEK").value(true))
                .andExpect(jsonPath("$.data.configured.QWEN_VL").value(false))
                .andExpect(jsonPath("$.data.maskedApiKeys.DEEPSEEK").value("sk-1234"))
                .andExpect(jsonPath("$.data.restartRequired").value(true))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist());
    }

    @Test
    void savesAndDeletesProviderCredential() throws Exception {
        mockMvc.perform(put("/api/settings/models/DEEPSEEK")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"sk-deepseek\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(credentialService).save(ModelProvider.DEEPSEEK, "sk-deepseek");

        mockMvc.perform(delete("/api/settings/models/QWEN_VL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(credentialService).delete(ModelProvider.QWEN_VL);
    }

    @Test
    void rejectsBlankKeyAndUnknownProvider() throws Exception {
        mockMvc.perform(put("/api/settings/models/DEEPSEEK")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(delete("/api/settings/models/unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
