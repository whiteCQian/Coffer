package com.coffer.model.runtime;

import com.coffer.config.EmbeddingProperties;
import com.coffer.config.ModelRuntimeProperties;
import com.coffer.entity.ModelProvider;
import com.coffer.entity.ModelRuntimeEndpoint;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.model.runtime.api.ModelRuntimeEndpointRequest;
import com.coffer.model.runtime.api.ModelRuntimeEndpointResponse;
import com.coffer.repository.ModelRuntimeEndpointRepository;
import com.coffer.service.ModelCredentialService;
import com.coffer.service.SecretCryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.List;

/** Resolves persisted runtime endpoint overrides over the existing environment defaults. */
@Service
@com.coffer.auth.service.OwnerOnly
@RequiredArgsConstructor
public class ModelRuntimeEndpointConfigurationService {

    private final ModelRuntimeEndpointRepository repository;
    private final ModelRuntimeProperties runtimeProperties;
    private final EmbeddingProperties embeddingProperties;
    private final ModelCredentialService credentialService;
    private final SecretCryptoService cryptoService;
    private final ApplicationEventPublisher eventPublisher;
    private final Environment environment;

    @Value("${coffer.models.allowed-api-hosts:api.deepseek.com,dashscope.aliyuncs.com}")
    private String allowedApiHosts;

    @Value("${coffer.runtime.local.allowed-hosts:localhost,127.0.0.1,::1}")
    private String allowedLocalHosts;
    @Value("${coffer.runtime.local.allowed-ports:11434,1234,8000}")
    private String allowedLocalPorts;

    @Transactional(readOnly = true)
    public List<ModelRuntimeEndpointResponse> list() {
        List<ModelRuntimeEndpointResponse> result = new ArrayList<>();
        for (GovernanceRunMode mode : GovernanceRunMode.values()) {
            for (ModelRuntimeCapability capability : ModelRuntimeCapability.values()) {
                result.add(toResponse(resolve(mode, capability)));
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public ResolvedModelRuntimeEndpoint resolve(GovernanceRunMode mode, ModelRuntimeCapability capability) {
        GovernanceRunMode resolvedMode = mode == null ? GovernanceRunMode.API : mode;
        ModelRuntimeCapability resolvedCapability = requireCapability(capability);
        Defaults defaults = defaults(resolvedMode, resolvedCapability);
        ModelRuntimeEndpoint override = repository.findByRunModeAndCapability(resolvedMode, resolvedCapability)
                .orElse(null);
        String baseUrl = override == null || isBlank(override.getBaseUrl())
                ? defaults.baseUrl : override.getBaseUrl();
        String modelName = override == null || isBlank(override.getModelName())
                ? defaults.modelName : override.getModelName();
        String apiKey = defaults.apiKey;
        if (resolvedMode == GovernanceRunMode.LOCAL && override != null
                && !isBlank(override.getEncryptedApiKey())) {
            apiKey = cryptoService.decrypt(override.getEncryptedApiKey());
        } else if (resolvedMode == GovernanceRunMode.API) {
            apiKey = credentialService.getApiKey(defaults.credentialProvider);
        }
        validateBaseUrl(resolvedMode, baseUrl);
        return new ResolvedModelRuntimeEndpoint(
                resolvedMode, resolvedCapability, baseUrl, modelName, apiKey,
                override == null ? "DEFAULT" : "CUSTOM", override != null,
                defaults.credentialProvider);
    }

    @Transactional
    public ModelRuntimeEndpointResponse save(ModelRuntimeEndpointRequest request) {
        GovernanceRunMode mode = requireMode(request.getMode());
        ModelRuntimeCapability capability = requireCapability(request.getCapability());
        String baseUrl = validateBaseUrl(mode, request.getBaseUrl());
        String modelName = requireText(request.getModelName(), "模型名称");
        ModelRuntimeEndpoint endpoint = repository.findByRunModeAndCapability(mode, capability)
                .orElseGet(() -> ModelRuntimeEndpoint.builder()
                        .runMode(mode).capability(capability).build());
        endpoint.setBaseUrl(baseUrl);
        endpoint.setModelName(modelName);
        if (mode == GovernanceRunMode.LOCAL && !isBlank(request.getApiKey())) {
            endpoint.setEncryptedApiKey(cryptoService.encrypt(request.getApiKey().trim()));
        }
        endpoint.setUpdatedAt(java.time.LocalDateTime.now());
        repository.save(endpoint);
        if (mode == GovernanceRunMode.API && !isBlank(request.getApiKey())) {
            credentialService.save(credentialProvider(capability), request.getApiKey());
        }
        publishChanged(mode, capability);
        return toResponse(resolve(mode, capability));
    }

    @Transactional
    public void delete(GovernanceRunMode mode, ModelRuntimeCapability capability) {
        GovernanceRunMode resolvedMode = requireMode(mode);
        ModelRuntimeCapability resolvedCapability = requireCapability(capability);
        repository.findByRunModeAndCapability(resolvedMode, resolvedCapability)
                .ifPresent(repository::delete);
        if (resolvedMode == GovernanceRunMode.API) {
            credentialService.delete(credentialProvider(resolvedCapability));
        }
        publishChanged(resolvedMode, resolvedCapability);
    }

    @Transactional(readOnly = true)
    public ResolvedModelRuntimeEndpoint resolveForTest(ModelRuntimeEndpointRequest request) {
        ResolvedModelRuntimeEndpoint current = resolve(request.getMode(), request.getCapability());
        String baseUrl = isBlank(request.getBaseUrl()) ? current.baseUrl() : request.getBaseUrl().trim();
        baseUrl = validateBaseUrl(current.mode(), baseUrl);
        String modelName = isBlank(request.getModelName()) ? current.modelName() : request.getModelName().trim();
        String apiKey = isBlank(request.getApiKey()) ? current.apiKey() : request.getApiKey().trim();
        return new ResolvedModelRuntimeEndpoint(current.mode(), current.capability(), baseUrl, modelName,
                apiKey, "REQUEST", current.customConfigured(), current.credentialProvider());
    }

    public ModelRuntimeEndpointResponse toResponse(ResolvedModelRuntimeEndpoint endpoint) {
        String masked = mask(endpoint.apiKey());
        return ModelRuntimeEndpointResponse.builder()
                .mode(endpoint.mode())
                .capability(endpoint.capability())
                .baseUrl(endpoint.baseUrl())
                .modelName(endpoint.modelName())
                .configured(endpoint.customConfigured() || !isBlank(endpoint.apiKey()))
                .maskedApiKey(masked)
                .credentialProvider(endpoint.credentialProvider() == null
                        ? null : endpoint.credentialProvider().name())
                .source(endpoint.source())
                .embeddingEnabled(embeddingProperties.isEnabled())
                .build();
    }

    private Defaults defaults(GovernanceRunMode mode, ModelRuntimeCapability capability) {
        ModelProvider provider = credentialProvider(capability);
        if (mode == GovernanceRunMode.LOCAL) {
            ModelRuntimeProperties.Endpoint endpoint = switch (capability) {
                case CHAT -> runtimeProperties.getLocal().getChat();
                case VISION -> runtimeProperties.getLocal().getVision();
                case EMBEDDING -> runtimeProperties.getLocal().getEmbedding();
            };
            return new Defaults(endpoint.getBaseUrl(), endpoint.getModelName(), "", provider);
        }
        return switch (capability) {
            case CHAT -> new Defaults(deepSeekBaseUrl(), deepSeekModel(), credentialService.getApiKey(provider), provider);
            case VISION -> new Defaults(qwenBaseUrl(), qwenModel(), credentialService.getApiKey(provider), provider);
            case EMBEDDING -> new Defaults(embeddingProperties.getBaseUrl(), embeddingProperties.getModelName(),
                    credentialService.getApiKey(provider), provider);
        };
    }

    private String deepSeekBaseUrl() {
        return property("coffer.models.deepseek.base-url", "https://api.deepseek.com/v1");
    }

    private String deepSeekModel() {
        return property("coffer.models.deepseek.model-name", "deepseek-chat");
    }

    private String qwenBaseUrl() {
        return property("coffer.models.qwen-vl.base-url", "https://dashscope.aliyuncs.com/compatible-mode/v1");
    }

    private String qwenModel() {
        return property("coffer.models.qwen-vl.model-name", "qwen-vl-max");
    }

    private String property(String key, String fallback) {
        return environment.getProperty(key, fallback);
    }

    private ModelProvider credentialProvider(ModelRuntimeCapability capability) {
        return capability == ModelRuntimeCapability.CHAT ? ModelProvider.DEEPSEEK : ModelProvider.QWEN_VL;
    }

    private GovernanceRunMode requireMode(GovernanceRunMode mode) {
        return mode == null ? GovernanceRunMode.API : mode;
    }

    private ModelRuntimeCapability requireCapability(ModelRuntimeCapability capability) {
        if (capability == null) {
            throw new IllegalArgumentException("模型能力不能为空");
        }
        return capability;
    }

    public void assertApiEndpointAllowed(String value) {
        validateBaseUrl(GovernanceRunMode.API, value);
    }

    private String validateBaseUrl(GovernanceRunMode mode, String value) {
        String baseUrl = requireText(value, "Base URL");
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Base URL 必须是有效的 HTTP(S) 地址");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !("http".equals(scheme) || "https".equals(scheme))) {
            throw new IllegalArgumentException("Base URL 必须是有效的 HTTP(S) 地址");
        }
        host = host.replace("[", "").replace("]", "").toLowerCase(Locale.ROOT);
        if (mode == GovernanceRunMode.API) {
            if (!"https".equals(scheme) || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !allowedHosts(allowedApiHosts).contains(host)) {
                throw new IllegalArgumentException("API 模式只允许已批准的 HTTPS 模型端点");
            }
        } else if (!allowedHosts(allowedLocalHosts).contains(host)
                || !allowedHosts(allowedLocalPorts).contains(String.valueOf(uri.getPort() == -1 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort()))) {
            throw new IllegalArgumentException("本地模型端点不在部署允许列表中");
        }
        String path = uri.getRawPath();
        if (path != null && (path.contains("%") || path.contains("..") || path.contains("\\")
                || !(path.isEmpty() || path.equals("/") || path.equals("/v1") || path.equals("/v1/")
                || path.equals("/compatible-mode/v1") || path.equals("/compatible-mode/v1/"))))
            throw new IllegalArgumentException("模型端点路径不在允许范围内");
        return baseUrl;
    }

    private Set<String> allowedHosts(String configured) {
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String requireText(String value, String field) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String mask(String apiKey) {
        if (isBlank(apiKey)) {
            return "";
        }
        if (apiKey.length() <= 7) {
            return "*".repeat(apiKey.length());
        }
        return apiKey.substring(0, 3) + "*".repeat(Math.max(4, apiKey.length() - 7))
                + apiKey.substring(apiKey.length() - 4);
    }

    private void publishChanged(GovernanceRunMode mode, ModelRuntimeCapability capability) {
        eventPublisher.publishEvent(new ModelRuntimeConfigurationChangedEvent(mode, capability));
    }

    private record Defaults(String baseUrl, String modelName, String apiKey, ModelProvider credentialProvider) {
    }
}
