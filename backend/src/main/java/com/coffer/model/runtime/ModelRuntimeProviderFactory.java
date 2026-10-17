package com.coffer.model.runtime;

import com.coffer.config.EmbeddingProperties;
import com.coffer.config.SafeModelHttpClient;
import com.coffer.entity.ModelProvider;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.model.provider.ChatProvider;
import com.coffer.model.provider.EmbeddingProvider;
import com.coffer.model.provider.VisionProvider;
import com.coffer.model.provider.openai.OpenAiCompatibleChatProvider;
import com.coffer.model.provider.openai.OpenAiCompatibleEmbeddingProvider;
import com.coffer.model.provider.openai.OpenAiCompatibleVisionProvider;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds concrete OpenAI-compatible providers for an explicit immutable mode snapshot. */
@Component
@com.coffer.auth.service.OwnerOnly
@RequiredArgsConstructor
public class ModelRuntimeProviderFactory {

    private final ModelRuntimeEndpointConfigurationService configurationService;
    private final EmbeddingProperties embeddingProperties;
    @org.springframework.beans.factory.annotation.Autowired private ModelRuntimeConnectivityTester connectivityTester;
    @org.springframework.beans.factory.annotation.Autowired private AdminEmbeddingService adminEmbedding;

    @Value("${coffer.models.deepseek.temperature:0.1}")
    private double deepSeekTemperature;
    @Value("${coffer.models.deepseek.max-tokens:2048}")
    private int deepSeekMaxTokens;
    @Value("${coffer.models.qwen-vl.temperature:0.1}")
    private double qwenTemperature;
    @Value("${coffer.models.qwen-vl.max-tokens:1024}")
    private int qwenMaxTokens;
    @Value("${coffer.models.qwen-vl.timeout-seconds:60}")
    private int qwenTimeoutSeconds;

    public ChatProvider chat(GovernanceRunMode mode) {
        ResolvedModelRuntimeEndpoint endpoint = endpoint(mode, ModelRuntimeCapability.CHAT);
        return new OpenAiCompatibleChatProvider(
                buildChat(endpoint, deepSeekTemperature, deepSeekMaxTokens, 60),
                providerId(endpoint, ModelProvider.DEEPSEEK), endpoint.modelName());
    }

    public VisionProvider vision(GovernanceRunMode mode) {
        ResolvedModelRuntimeEndpoint endpoint = endpoint(mode, ModelRuntimeCapability.VISION);
        return new OpenAiCompatibleVisionProvider(
                buildChat(endpoint, qwenTemperature, qwenMaxTokens, qwenTimeoutSeconds),
                providerId(endpoint, ModelProvider.QWEN_VL), endpoint.modelName());
    }

    public EmbeddingProvider embedding(GovernanceRunMode mode) {
        ModelExecutionContext.require();
        var execution = adminEmbedding.execution(endpoint(mode, ModelRuntimeCapability.EMBEDDING), mode);
        ResolvedModelRuntimeEndpoint endpoint = execution.endpoint();
        OpenAiEmbeddingModel delegate = buildEmbedding(endpoint, execution.dimensions());
        return new OpenAiCompatibleEmbeddingProvider(delegate,
                "ADMIN_EMBEDDING", endpoint.modelName());
    }

    /** Performs real connectivity checks used by the C15 mode validation gate. */
    public ModelRuntimeValidationResult validate(GovernanceRunMode mode) {
        Map<String, String> capabilities = new LinkedHashMap<>();
        validateChat(mode, capabilities);
        validateVision(mode, capabilities);
        capabilities.put("embedding", configurationService.embeddingAvailable(mode) ? "MANAGED" : "DISABLED");
        boolean success = capabilities.values().stream()
                .allMatch(value -> "OK".equals(value) || "DISABLED".equals(value) || "MANAGED".equals(value));
        return new ModelRuntimeValidationResult(success, capabilities,
                success ? "模型能力校验通过" : "至少一项模型能力校验失败");
    }

    private void validateChat(GovernanceRunMode mode, Map<String, String> capabilities) {
        validateCapability(mode, ModelRuntimeCapability.CHAT, "chat", capabilities);
    }

    private void validateVision(GovernanceRunMode mode, Map<String, String> capabilities) {
        validateCapability(mode, ModelRuntimeCapability.VISION, "vision", capabilities);
    }

    private void validateCapability(GovernanceRunMode mode, ModelRuntimeCapability capability,
                                    String name, Map<String,String> capabilities) {
        var result = connectivityTester.test(configurationService.resolve(mode, capability));
        capabilities.put(name, result.isSuccess() ? "OK" : result.getMessage());
    }

    private void validateEmbedding(GovernanceRunMode mode, Map<String, String> capabilities) {
        try {
            requireCredentials(mode, ModelRuntimeCapability.EMBEDDING);
            embedding(mode).embed("Coffer runtime mode validation");
            capabilities.put("embedding", "OK");
        } catch (Exception e) {
            capabilities.put("embedding", safeMessage());
        }
    }

    private ResolvedModelRuntimeEndpoint requireCredentials(
            GovernanceRunMode mode, ModelRuntimeCapability capability) {
        ResolvedModelRuntimeEndpoint endpoint = configurationService.resolve(mode, capability);
        if (mode == GovernanceRunMode.API && endpoint.apiKey().isBlank()) {
            throw new IllegalStateException(capability.name() + " 模式尚未配置 API Key");
        }
        return endpoint;
    }

    private OpenAiChatModel buildChat(ResolvedModelRuntimeEndpoint endpoint,
                                      double temperature, int maxTokens, int timeoutSeconds) {
        return OpenAiChatModel.builder()
                .logRequests(false).logResponses(false).maxRetries(0)
                .httpClientBuilder(SafeModelHttpClient.builder())
                .baseUrl(requireText(endpoint.baseUrl(), "Chat Base URL"))
                .apiKey(apiKey(endpoint.apiKey()))
                .modelName(requireText(endpoint.modelName(), "模型名称"))
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    private ResolvedModelRuntimeEndpoint endpoint(GovernanceRunMode mode, ModelRuntimeCapability capability) {
        var snapshot = ModelExecutionContext.current();
        if (snapshot == null) return configurationService.resolve(mode, capability); // Connectivity probes use synthetic text only.
        if (snapshot.mode() != mode || !snapshot.endpoints().containsKey(capability)) throw new ModelConsentRequiredException();
        return snapshot.endpoints().get(capability);
    }

    private OpenAiEmbeddingModel buildEmbedding(ResolvedModelRuntimeEndpoint endpoint, Integer dimensions) {
        return OpenAiEmbeddingModel.builder()
                .logRequests(false).logResponses(false).maxRetries(0)
                .httpClientBuilder(SafeModelHttpClient.builder())
                .baseUrl(requireText(endpoint.baseUrl(), "Embedding Base URL"))
                .apiKey(apiKey(endpoint.apiKey()))
                .modelName(requireText(endpoint.modelName(), "Embedding 模型名称"))
                .dimensions(dimensions)
                .timeout(Duration.ofSeconds(embeddingProperties.getTimeoutSeconds()))
                .build();
    }

    private String providerId(ResolvedModelRuntimeEndpoint endpoint, ModelProvider apiProvider) {
        return endpoint.mode() == GovernanceRunMode.LOCAL ? "LOCAL" : apiProvider.name();
    }

    private String apiKey(String value) {
        return value == null || value.isBlank() ? "not-required" : value;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " 未配置");
        }
        return value.trim();
    }

    private String safeMessage() {
        return "模型运行时配置无效，请检查模型端点和凭据";
    }
}
