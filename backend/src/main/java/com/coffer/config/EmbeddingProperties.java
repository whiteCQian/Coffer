package com.coffer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the optional OpenAI-compatible embedding model. */
@Data
@ConfigurationProperties(prefix = "coffer.embedding")
public class EmbeddingProperties {

    private boolean enabled;
    private String baseUrl;
    private String modelName;
    private Integer dimensions;
    private long timeoutSeconds = 30;
}
