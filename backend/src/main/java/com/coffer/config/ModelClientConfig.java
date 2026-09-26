package com.coffer.config;

import com.coffer.entity.ModelProvider;
import com.coffer.model.provider.ChatProvider;
import com.coffer.model.provider.VisionProvider;
import com.coffer.model.runtime.ModeAwareChatProvider;
import com.coffer.model.runtime.ModeAwareVisionProvider;
import com.coffer.model.runtime.ModelRuntimeModeService;
import com.coffer.model.runtime.ModelRuntimeProviderFactory;
import com.coffer.model.provider.openai.OpenAiCompatibleChatProvider;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * Creates the default OpenAI-compatible Chat and Vision Providers.
 *
 * <p>Concrete LangChain4j clients are confined to this configuration boundary;
 * business services depend on the Provider abstractions instead.</p>
 */
@Configuration
@EnableConfigurationProperties(ModelRuntimeProperties.class)
public class ModelClientConfig {

    @Bean
    @Primary
    public ChatProvider chatProvider(ModelRuntimeModeService modeService,
                                     ModelRuntimeProviderFactory providerFactory) {
        return new ModeAwareChatProvider(modeService, providerFactory);
    }

    @Bean
    public VisionProvider visionProvider(
            ModelRuntimeModeService modeService,
            ModelRuntimeProviderFactory providerFactory) {
        return new ModeAwareVisionProvider(modeService, providerFactory);
    }

    /** Builds a short-lived provider used only by the model credential connectivity test. */
    public static ChatProvider buildTestClient(ModelProvider provider, String apiKey,
                                                String deepSeekBaseUrl, String deepSeekModel,
                                                String qwenBaseUrl, String qwenModel) {
        String baseUrl = provider == ModelProvider.DEEPSEEK ? deepSeekBaseUrl : qwenBaseUrl;
        String modelName = provider == ModelProvider.DEEPSEEK ? deepSeekModel : qwenModel;
        OpenAiChatModel delegate = OpenAiChatModel.builder()
                .logRequests(false).logResponses(false).maxRetries(0)
                .httpClientBuilder(SafeModelHttpClient.builder())
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(30))
                .build();
        return new OpenAiCompatibleChatProvider(delegate, provider.name(), modelName);
    }

}
