package com.coffer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Controls the optional vector + lexical hybrid search route. */
@Data
@ConfigurationProperties(prefix = "coffer.hybrid")
public class HybridSearchProperties {

    /** Feature flag. Disabled keeps the original FileSearchTool route unchanged. */
    private boolean enabled;

    /** Number of candidates requested from Redis and MySQL before fusion. */
    private int topK = 50;

    /** RRF smoothing constant. */
    private int rrfK = 60;

    /** Maximum time a search request waits for the vector route. */
    private long vectorTimeoutMs = 1500;
}
