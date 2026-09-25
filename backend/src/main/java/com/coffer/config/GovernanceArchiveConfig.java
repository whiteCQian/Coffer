package com.coffer.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Enables C07 archive execution policy binding. */
@Configuration
@EnableConfigurationProperties(GovernanceArchiveProperties.class)
public class GovernanceArchiveConfig {
}
