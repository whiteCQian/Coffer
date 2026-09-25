package com.coffer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime policy for confirmed governance archive execution. */
@Data
@ConfigurationProperties(prefix = "coffer.governance.archive")
public class GovernanceArchiveProperties {

    /** Delay before a failed item becomes eligible for an automatic retry. */
    private long retryDelaySeconds = 30;

    /** Maximum number of external execution attempts recorded for one item. */
    private int maxAttempts = 3;
}
