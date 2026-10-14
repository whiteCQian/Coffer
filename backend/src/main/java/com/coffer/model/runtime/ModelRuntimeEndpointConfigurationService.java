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
import java.util.*;
import java.util.stream.Collectors;

/** User-managed Chat/Vision; Embedding is resolved exclusively from admin settings. */
@Service @com.coffer.auth.service.OwnerOnly @RequiredArgsConstructor
public class ModelRuntimeEndpointConfigurationService {
    private final ModelRuntimeEndpointRepository repository;
    private final ModelRuntimeProperties runtimeProperties;
    private final EmbeddingProperties embeddingProperties;
    private final ModelCredentialService credentialService;
    private final SecretCryptoService cryptoService;
    private final ApplicationEventPublisher eventPublisher;
    private final Environment environment;
    @org.springframework.beans.factory.annotation.Autowired private AdminEmbeddingService adminEmbedding;
    @Value("${coffer.models.allowed-api-hosts:api.deepseek.com,dashscope.aliyuncs.com,maas.qianwenaiapi.com}") private String allowedApiHosts;
    @Value("${coffer.runtime.local.allowed-hosts:localhost,127.0.0.1,::1}") private String allowedLocalHosts;
    @Value("${coffer.runtime.local.allowed-ports:11434,1234,8000}") private String allowedLocalPorts;

    @Transactional(readOnly = true)
    public List<ModelRuntimeEndpointResponse> list() {
        var result = new ArrayList<ModelRuntimeEndpointResponse>();
        for (var mode : GovernanceRunMode.values()) for (var capability : List.of(ModelRuntimeCapability.CHAT, ModelRuntimeCapability.VISION))
            result.add(toResponse(resolveConfiguration(mode, capability)));
        return result;
    }
    @Transactional(readOnly = true)
    public ResolvedModelRuntimeEndpoint resolve(GovernanceRunMode mode, ModelRuntimeCapability capability) {
        if (capability == ModelRuntimeCapability.EMBEDDING) return adminEmbedding.metadata(requireMode(mode));
        var endpoint = resolveConfiguration(mode, capability);
        validateBaseUrl(endpoint.mode(), endpoint.baseUrl()); return endpoint;
    }
    public boolean embeddingAvailable(GovernanceRunMode mode) { return adminEmbedding.isAvailable(requireMode(mode)); }
    private ResolvedModelRuntimeEndpoint resolveConfiguration(GovernanceRunMode mode, ModelRuntimeCapability capability) {
        requireUserManagedCapability(capability); mode = requireMode(mode);
        var override = repository.findByRunModeAndCapability(mode, capability).orElse(null);
        var provider = capability == ModelRuntimeCapability.CHAT ? ModelProvider.DEEPSEEK : ModelProvider.QWEN_VL;
        String url, model, key = "";
        if (mode == GovernanceRunMode.LOCAL) {
            var defaults = capability == ModelRuntimeCapability.CHAT ? runtimeProperties.getLocal().getChat() : runtimeProperties.getLocal().getVision();
            url = defaults.getBaseUrl(); model = defaults.getModelName();
            if (override != null && !isBlank(override.getEncryptedApiKey())) key = cryptoService.decrypt(override.getEncryptedApiKey());
        } else {
            String prefix = capability == ModelRuntimeCapability.CHAT ? "coffer.models.deepseek." : "coffer.models.qwen-vl.";
            url = environment.getProperty(prefix + "base-url", capability == ModelRuntimeCapability.CHAT ? "https://api.deepseek.com/v1" : "https://dashscope.aliyuncs.com/compatible-mode/v1");
            model = environment.getProperty(prefix + "model-name", capability == ModelRuntimeCapability.CHAT ? "deepseek-chat" : "qwen-vl-max");
            key = credentialService.getApiKey(provider);
        }
        if (override != null) { if (!isBlank(override.getBaseUrl())) url = override.getBaseUrl(); if (!isBlank(override.getModelName())) model = override.getModelName(); }
        return new ResolvedModelRuntimeEndpoint(mode, capability, url, model, key, override == null ? "DEFAULT" : "CUSTOM", override != null, provider);
    }
    @Transactional
    public ModelRuntimeEndpointResponse save(ModelRuntimeEndpointRequest request) {
        requireUserManagedCapability(request.getCapability()); rejectSharing(request);
        var mode = requireMode(request.getMode()); var capability = request.getCapability();
        String url = validateBaseUrl(mode, request.getBaseUrl()), model = requireText(request.getModelName(), "模型名称");
        var endpoint = repository.findByRunModeAndCapability(mode, capability).orElseGet(() -> ModelRuntimeEndpoint.builder().runMode(mode).capability(capability).build());
        endpoint.setBaseUrl(url); endpoint.setModelName(model); endpoint.setUseChatCredentials(false);
        if (mode == GovernanceRunMode.LOCAL && !isBlank(request.getApiKey())) endpoint.setEncryptedApiKey(cryptoService.encrypt(request.getApiKey().trim()));
        endpoint.setUpdatedAt(java.time.LocalDateTime.now()); repository.save(endpoint);
        if (mode == GovernanceRunMode.API && !isBlank(request.getApiKey())) credentialService.save(capability == ModelRuntimeCapability.CHAT ? ModelProvider.DEEPSEEK : ModelProvider.QWEN_VL, request.getApiKey());
        publishChanged(mode, capability); return toResponse(resolve(mode, capability));
    }
    @Transactional
    public void delete(GovernanceRunMode mode, ModelRuntimeCapability capability) {
        requireUserManagedCapability(capability); mode = requireMode(mode);
        repository.findByRunModeAndCapability(mode, capability).ifPresent(repository::delete);
        if (mode == GovernanceRunMode.API) credentialService.delete(capability == ModelRuntimeCapability.CHAT ? ModelProvider.DEEPSEEK : ModelProvider.QWEN_VL);
        publishChanged(mode, capability);
    }
    @Transactional(readOnly = true)
    public ResolvedModelRuntimeEndpoint resolveForTest(ModelRuntimeEndpointRequest request) {
        requireUserManagedCapability(request.getCapability()); rejectSharing(request);
        var current = resolveConfiguration(request.getMode(), request.getCapability());
        String url = validateBaseUrl(current.mode(), isBlank(request.getBaseUrl()) ? current.baseUrl() : request.getBaseUrl().trim());
        return new ResolvedModelRuntimeEndpoint(current.mode(), current.capability(), url,
                isBlank(request.getModelName()) ? current.modelName() : request.getModelName().trim(),
                isBlank(request.getApiKey()) ? current.apiKey() : request.getApiKey().trim(), "REQUEST", current.customConfigured(), current.credentialProvider());
    }
    public ModelRuntimeEndpointResponse toResponse(ResolvedModelRuntimeEndpoint endpoint) {
        requireUserManagedCapability(endpoint.capability());
        return ModelRuntimeEndpointResponse.builder().mode(endpoint.mode()).capability(endpoint.capability())
                .baseUrl(endpoint.baseUrl()).modelName(endpoint.modelName()).configured(endpoint.customConfigured() || !isBlank(endpoint.apiKey()))
                .maskedApiKey(mask(endpoint.apiKey())).credentialProvider(endpoint.credentialProvider().name()).source(endpoint.source())
                .embeddingEnabled(false).useChatCredentials(false).build();
    }
    private void requireUserManagedCapability(ModelRuntimeCapability capability) {
        if (capability == null) throw new IllegalArgumentException("模型能力不能为空");
        if (capability == ModelRuntimeCapability.EMBEDDING) throw new org.springframework.security.access.AccessDeniedException("语义向量检索由管理员统一配置");
    }
    private void rejectSharing(ModelRuntimeEndpointRequest request) {
        if (Boolean.TRUE.equals(request.getUseChatCredentials())) throw new IllegalArgumentException("个人模型配置不再提供向量 Key 共享");
    }
    private GovernanceRunMode requireMode(GovernanceRunMode mode) { return mode == null ? GovernanceRunMode.API : mode; }

    public void assertApiEndpointAllowed(String value) {
        validateBaseUrl(GovernanceRunMode.API, value);
    }

    private String validateBaseUrl(GovernanceRunMode mode, String value) {
        return validateAddress(mode, value, allowedApiHosts, allowedLocalHosts, allowedLocalPorts);
    }

    public static String validateAddress(GovernanceRunMode mode, String value, String allowedApiHosts,
                                         String allowedLocalHosts, String allowedLocalPorts) {
        if (isBlank(value)) throw new ModelEndpointValidationException(ModelEndpointValidationException.Reason.EMPTY_URL);
        String baseUrl = value.trim();
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new ModelEndpointValidationException(ModelEndpointValidationException.Reason.INVALID_URL);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !("http".equals(scheme) || "https".equals(scheme))) {
            throw new ModelEndpointValidationException(ModelEndpointValidationException.Reason.INVALID_URL);
        }
        host = host.replace("[", "").replace("]", "").toLowerCase(Locale.ROOT);
        if (mode == GovernanceRunMode.API) {
            if (!"https".equals(scheme) || (uri.getPort() != -1 && uri.getPort() != 443))
                throw new ModelEndpointValidationException(ModelEndpointValidationException.Reason.HTTPS_REQUIRED);
            if (!allowedHosts(allowedApiHosts).contains(host))
                throw new ModelEndpointValidationException(ModelEndpointValidationException.Reason.HOST_NOT_ALLOWED);
        } else if (!allowedHosts(allowedLocalHosts).contains(host)
                || !allowedHosts(allowedLocalPorts).contains(String.valueOf(uri.getPort() == -1 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort()))) {
            throw new ModelEndpointValidationException(ModelEndpointValidationException.Reason.LOCAL_NOT_ALLOWED);
        }
        String path = uri.getRawPath();
        if (path != null && (path.contains("%") || path.contains("..") || path.contains("\\")
                || !(path.isEmpty() || path.equals("/") || path.equals("/v1") || path.equals("/v1/")
                || path.equals("/compatible-mode/v1") || path.equals("/compatible-mode/v1/"))))
            throw new ModelEndpointValidationException(ModelEndpointValidationException.Reason.INCOMPATIBLE_PATH);
        return baseUrl;
    }

    private static Set<String> allowedHosts(String configured) {
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String requireText(String value, String field) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.trim();
    }

    private static boolean isBlank(String value) {
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
}
