package com.coffer.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers inbox importer configuration properties. */
@Configuration
@EnableConfigurationProperties(InboxImportProperties.class)
public class InboxImportConfig {
}
