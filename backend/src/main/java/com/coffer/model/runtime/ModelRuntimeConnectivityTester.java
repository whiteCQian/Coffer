package com.coffer.model.runtime;

import com.coffer.model.provider.openai.OpenAiCompatibleVisionProvider;
import com.coffer.config.SafeModelHttpClient;
import com.coffer.model.runtime.api.ModelRuntimeEndpointTestResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Performs an isolated connectivity test without changing the active runtime mode. */
@Component
@RequiredArgsConstructor
public class ModelRuntimeConnectivityTester {

    private final com.coffer.config.EmbeddingProperties embeddingProperties;

    public ModelRuntimeEndpointTestResponse test(ResolvedModelRuntimeEndpoint endpoint) {
        return test(endpoint, embeddingProperties.getDimensions());
    }

    public ModelRuntimeEndpointTestResponse test(ResolvedModelRuntimeEndpoint endpoint, Integer dimensions) {
        if (endpoint.mode() == com.coffer.governance.domain.GovernanceRunMode.API
                && (endpoint.apiKey() == null || endpoint.apiKey().isBlank())) {
            String capability = switch (endpoint.capability()) {
                case CHAT -> "Chat";
                case VISION -> "Vision";
                case EMBEDDING -> "Embedding（RAG）";
            };
            return ModelRuntimeEndpointTestResponse.builder().success(false).capability(endpoint.capability())
                    .message(capability + " 未配置 API Key，请填写该端点对应的 Key 后再测试。")
                    .build();
        }
        try {
            switch (endpoint.capability()) {
                case CHAT -> buildChat(endpoint, 2048, 60).chat("Reply with OK only.");
                case VISION -> new OpenAiCompatibleVisionProvider(
                        buildChat(endpoint, 1024, 60), endpoint.mode().name(), endpoint.modelName())
                        .chat("Reply with OK only.");
                case EMBEDDING -> {
                    float[] vector = buildEmbedding(endpoint, dimensions).embed("Coffer runtime endpoint validation").content().vector();
                    if (dimensions != null && vector.length != dimensions)
                        return ModelRuntimeEndpointTestResponse.builder().success(false).capability(endpoint.capability())
                                .message("向量服务返回的维度与配置不一致，请检查维度设置").build();
                }
            }
            return ModelRuntimeEndpointTestResponse.builder()
                    .success(true).capability(endpoint.capability()).message("连接成功").build();
        } catch (Exception e) {
            return ModelRuntimeEndpointTestResponse.builder()
                    .success(false)
                    .capability(endpoint.capability())
                    .message(safeMessage(e))
                    .build();
        }
    }

    private OpenAiChatModel buildChat(ResolvedModelRuntimeEndpoint endpoint, int maxTokens, int timeoutSeconds) {
        return OpenAiChatModel.builder()
                .logRequests(false).logResponses(false).maxRetries(0)
                .httpClientBuilder(SafeModelHttpClient.builder())
                .baseUrl(requireText(endpoint.baseUrl(), "Base URL"))
                .apiKey(apiKey(endpoint.apiKey()))
                .modelName(requireText(endpoint.modelName(), "模型名称"))
                .temperature(0.1)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    private OpenAiEmbeddingModel buildEmbedding(ResolvedModelRuntimeEndpoint endpoint, Integer dimensions) {
        return OpenAiEmbeddingModel.builder()
                .logRequests(false).logResponses(false).maxRetries(0)
                .httpClientBuilder(SafeModelHttpClient.builder())
                .baseUrl(requireText(endpoint.baseUrl(), "Base URL"))
                .apiKey(apiKey(endpoint.apiKey()))
                .modelName(requireText(endpoint.modelName(), "模型名称"))
                .dimensions(dimensions)
                .timeout(Duration.ofSeconds(embeddingProperties.getTimeoutSeconds()))
                .build();
    }

    private String apiKey(String value) {
        return value == null || value.isBlank() ? "not-required" : value;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 未配置");
        }
        return value.trim();
    }

    private String safeMessage(Exception failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof dev.langchain4j.exception.HttpException http) {
                return switch (http.statusCode()) {
                    case 401, 403 -> "API Key 验证失败或没有访问权限，请确认 Key 与当前服务端点及地域匹配";
                    case 400, 404 -> "模型、接口或参数配置不匹配；RAG 请使用支持 Embedding 的向量模型和端点";
                    case 402 -> "模型服务额度或余额不足，请在对应服务控制台核查";
                    case 429 -> "模型服务限流，请稍后重试并检查服务额度";
                    default -> "模型服务返回异常，请稍后重试";
                };
            }
        }
        if (failure instanceof dev.langchain4j.exception.AuthenticationException)
            return "API Key 验证失败或没有访问权限，请确认 Key 与当前服务端点及地域匹配";
        if (failure instanceof dev.langchain4j.exception.InvalidRequestException)
            return "模型、接口或参数配置不匹配；RAG 请使用支持 Embedding 的向量模型和端点";
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.io.IOException)
                return "模型服务网络连接失败，请检查服务器到当前模型端点的连接";
        }
        return "连接失败，请检查模型端点、模型名称和密钥配置";
    }
}
