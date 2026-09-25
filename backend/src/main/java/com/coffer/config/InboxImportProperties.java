package com.coffer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the first-version periodic inbox importer. */
@Data
@ConfigurationProperties(prefix = "coffer.import.inbox")
public class InboxImportProperties {

    /** Disabled by default until a local inbox directory is explicitly configured. */
    private boolean enabled;

    /** Absolute or relative directory containing files to import. */
    private String directory = "";

    /** Delay between the end of two scans. */
    private long fixedDelayMs = 30_000L;

    /** Number of unchanged observations required before import. */
    private int stableObservationThreshold = 2;

    /** Maximum number of regular files inspected in one scan. */
    private int maxFilesPerScan = 50;

    /** Delay before a failed snapshot is attempted again. */
    private long retryDelayMs = 60_000L;
}
