package com.coffer.web;

import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data @Component @Validated @ConfigurationProperties("coffer.web.limits")
public class WebLimitProperties {
    @Min(1) private long accountBytes = 10L * 1024 * 1024 * 1024;
    @Min(1) private long totalBytes = 100L * 1024 * 1024 * 1024;
    @Min(1) private int accountObjects = 10000;
    @Min(1) private int pendingTasks = 20;
    @Min(1) @Max(16) private int concurrentTasks = 2;
    @Min(1) private long reserveFreeBytes = 2L * 1024 * 1024 * 1024;
    @Min(1) @Max(99) private int reserveFreePercent = 5;
    @Min(1) private int capacityMaxAgeSeconds = 30;
    private String capacityReceipt = "/capacity/minio-capacity.json";
}
