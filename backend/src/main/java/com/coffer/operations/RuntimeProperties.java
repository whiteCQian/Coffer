package com.coffer.operations;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

@Data @Component @Validated @ConfigurationProperties("coffer.operations")
public class RuntimeProperties {
    private String storageVolume = "";
    private String backupReceipt = "";
    @Min(1) private long minimumFreeBytes = 512L * 1024 * 1024;
    @Min(1) @Max(99) private int minimumFreePercent = 5;
    @Min(1) private long backupMaxAgeHours = 48;
    @Min(1) private long backlogWarningCount = 100;
    @Min(1) private long backlogMaxAgeMinutes = 30;
}
