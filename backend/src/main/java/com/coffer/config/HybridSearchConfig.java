package com.coffer.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers configuration for the optional hybrid search route. */
@Configuration
@EnableConfigurationProperties(HybridSearchProperties.class)
public class HybridSearchConfig {
}
