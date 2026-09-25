package com.coffer.inbox.api.dto;

import com.coffer.inbox.domain.InboxImportStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Recent inbox snapshot shown in the batch-import progress panel. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboxImportItemResponse {

    private String fileName;
    private InboxImportStatus status;
    private Integer stableObservations;
    private Integer attemptCount;
    private String taskId;
    private String error;
    private LocalDateTime updatedAt;
}
