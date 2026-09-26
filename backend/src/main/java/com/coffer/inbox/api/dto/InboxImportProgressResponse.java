package com.coffer.inbox.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** Aggregated progress snapshot for the configured inbox importer. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboxImportProgressResponse {
    private boolean awaitingModelConsent;

    private boolean enabled;
    private String directory;
    private LocalDateTime lastScanAt;
    private int totalCount;
    private int discoveredCount;
    private int stableCount;
    private int importingCount;
    private int importedCount;
    private int duplicateCount;
    private int failedCount;
    private int unsupportedCount;
    private List<InboxImportItemResponse> items;
}
