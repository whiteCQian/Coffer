package com.coffer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for dry-run preview retention and scheduled expiry. */
@Data
@ConfigurationProperties(prefix = "coffer.governance.preview")
public class GovernancePreviewProperties {

    /** How long a preview remains executable before C06 confirmation. */
    private long expirationMinutes = 24 * 60L;

    /** Delay between expiry sweeps. */
    private long expiryScanDelayMs = 60_000L;

    /** Non-secret model identifier recorded on preview items. */
    private String analysisModel = "configured-chat-model";
}
