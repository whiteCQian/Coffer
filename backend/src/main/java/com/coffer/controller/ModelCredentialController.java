package com.coffer.controller;

import com.coffer.config.ModelClientConfig;
import com.coffer.dto.ModelCredentialStatusResponse;
import com.coffer.dto.ModelCredentialTestResponse;
import com.coffer.dto.Result;
import com.coffer.dto.SaveModelCredentialRequest;
import com.coffer.dto.TestModelCredentialRequest;
import com.coffer.entity.ModelProvider;
import com.coffer.model.provider.ChatProvider;
import com.coffer.service.ModelCredentialService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.stream.Collectors;

/** Local single-user settings API for encrypted model credentials. */
@Slf4j
@RestController
@RequestMapping("/api/settings/models")
@RequiredArgsConstructor
public class ModelCredentialController {

    private final ModelCredentialService credentialService;

    @Value("${coffer.models.deepseek.base-url:https://api.deepseek.com/v1}")
    private String deepSeekBaseUrl;
    @Value("${coffer.models.deepseek.model-name:deepseek-chat}")
    private String deepSeekModel;
    @Value("${coffer.models.qwen-vl.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String qwenBaseUrl;
    @Value("${coffer.models.qwen-vl.model-name:qwen-vl-max}")
    private String qwenModel;

    @GetMapping
    public Result<ModelCredentialStatusResponse> getStatus() {
        Map<ModelProvider, Boolean> providerStatus = credentialService.status();
        Map<String, Boolean> status = providerStatus.entrySet().stream()
                .collect(Collectors.toMap(entry -> entry.getKey().name(), Map.Entry::getValue));
        Map<String, String> maskedApiKeys = providerStatus.keySet().stream()
                .collect(Collectors.toMap(ModelProvider::name, credentialService::getMaskedApiKey));
        return Result.success(ModelCredentialStatusResponse.builder()
                .configured(status)
                .maskedApiKeys(maskedApiKeys)
                .restartRequired(true)
                .build());
    }

    @PutMapping("/{provider}")
    public Result<Void> save(@PathVariable String provider,
                             @Valid @RequestBody SaveModelCredentialRequest request) {
        credentialService.save(parseProvider(provider), request.getApiKey());
        return Result.success();
    }

    @DeleteMapping("/{provider}")
    public Result<Void> delete(@PathVariable String provider) {
        credentialService.delete(parseProvider(provider));
        return Result.success();
    }

    @PostMapping("/{provider}/test")
    public Result<ModelCredentialTestResponse> test(@PathVariable String provider,
                                                    @Valid @RequestBody TestModelCredentialRequest request) {
        ModelProvider modelProvider = parseProvider(provider);
        try {
            ChatProvider client = ModelClientConfig.buildTestClient(
                    modelProvider, request.getApiKey().trim(), deepSeekBaseUrl, deepSeekModel,
                    qwenBaseUrl, qwenModel);
            client.chat("Reply with OK only.");
            return Result.success(ModelCredentialTestResponse.builder()
                    .success(true).message("连接成功").build());
        } catch (Exception e) {
            log.warn("模型连接测试失败 provider={}: {}", modelProvider, e.getMessage());
            return Result.success(ModelCredentialTestResponse.builder()
                    .success(false).message(safeTestMessage(e)).build());
        }
    }

    private ModelProvider parseProvider(String value) {
        try {
            return ModelProvider.valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("不支持的模型提供商: " + value);
        }
    }

    private String safeTestMessage(Exception e) {
        if (e.getMessage() == null || e.getMessage().isBlank()) {
            return "连接失败，请检查密钥和网络配置";
        }
        String message = e.getMessage();
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
