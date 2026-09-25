package com.coffer.config;

import com.coffer.entity.ModelProvider;
import com.coffer.model.provider.EmbeddingProvider;
import com.coffer.model.runtime.ModeAwareEmbeddingProvider;
import com.coffer.model.runtime.ModelRuntimeModeService;
import com.coffer.model.runtime.ModelRuntimeProviderFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Creates the optional embedding client from the encrypted Qwen credential.
 *
 * <p>The bean is deliberately disabled by default. This keeps the existing
 * application startup independent of an external embedding provider while
 * making the provider ready for the next hybrid-search step.</p>
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingConfig {

    private final EmbeddingProperties properties;

    @Bean
    @ConditionalOnProperty(prefix = "coffer.embedding", name = "enabled", havingValue = "true")
    public EmbeddingProvider embeddingProvider(ModelRuntimeModeService modeService,
                                               ModelRuntimeProviderFactory providerFactory) {
        return new ModeAwareEmbeddingProvider(modeService, providerFactory);
    }
}
