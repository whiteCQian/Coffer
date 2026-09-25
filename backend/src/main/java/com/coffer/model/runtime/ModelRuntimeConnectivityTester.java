package com.coffer.model.runtime;

import com.coffer.model.provider.openai.OpenAiCompatibleVisionProvider;
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
        try {
            switch (endpoint.capability()) {
                case CHAT -> buildChat(endpoint, 2048, 60).chat("Reply with OK only.");
                case VISION -> new OpenAiCompatibleVisionProvider(
                        buildChat(endpoint, 1024, 60), endpoint.mode().name(), endpoint.modelName())
                        .chat("Reply with OK only.");
                case EMBEDDING -> buildEmbedding(endpoint).embed("Coffer runtime endpoint validation");
            }
            return ModelRuntimeEndpointTestResponse.builder()
                    .success(true).capability(endpoint.capability()).message("连接成功").build();
        } catch (Exception e) {
            return ModelRuntimeEndpointTestResponse.builder()
                    .success(false)
                    .capability(endpoint.capability())
                    .message(safeMessage(e, endpoint.apiKey()))
                    .build();
        }
    }

    private OpenAiChatModel buildChat(ResolvedModelRuntimeEndpoint endpoint, int maxTokens, int timeoutSeconds) {
        return OpenAiChatModel.builder()
                .baseUrl(requireText(endpoint.baseUrl(), "Base URL"))
                .apiKey(apiKey(endpoint.apiKey()))
                .modelName(requireText(endpoint.modelName(), "模型名称"))
                .temperature(0.1)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    private OpenAiEmbeddingModel buildEmbedding(ResolvedModelRuntimeEndpoint endpoint) {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(requireText(endpoint.baseUrl(), "Base URL"))
                .apiKey(apiKey(endpoint.apiKey()))
                .modelName(requireText(endpoint.modelName(), "模型名称"))
                .dimensions(embeddingProperties.getDimensions())
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

    private String safeMessage(Exception e, String apiKey) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "连接失败，请检查端点、模型名称和密钥";
        }
        if (apiKey != null && !apiKey.isBlank()) {
            message = message.replace(apiKey, "***");
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
