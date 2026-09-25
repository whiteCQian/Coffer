package com.coffer.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers and schedules governance preview lifecycle configuration. */
@Configuration
@EnableConfigurationProperties(GovernancePreviewProperties.class)
public class GovernancePreviewConfig {
}
