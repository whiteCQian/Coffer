package com.coffer.config;

import com.coffer.model.provider.EmbeddingProvider;
import com.coffer.model.runtime.ModeAwareEmbeddingProvider;
import com.coffer.model.runtime.ModelRuntimeModeService;
import com.coffer.model.runtime.ModelRuntimeProviderFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * Registers the router without making network calls at startup. The admin
 * configuration and owner consent decide whether embedding can execute.
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingConfig {

    private final EmbeddingProperties properties;

    @Bean
    public EmbeddingProvider embeddingProvider(ModelRuntimeModeService modeService,
                                               ModelRuntimeProviderFactory providerFactory,
                                               com.coffer.model.runtime.ModelExecutionSnapshotService snapshots) {
        return new ModeAwareEmbeddingProvider(modeService, providerFactory, snapshots);
    }
}
