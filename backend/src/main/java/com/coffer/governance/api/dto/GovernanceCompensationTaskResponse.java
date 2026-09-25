package com.coffer.governance.api.dto;

import com.coffer.governance.domain.GovernanceCompensationAction;
import com.coffer.governance.domain.GovernanceCompensationStatus;
import java.time.LocalDateTime;

public record GovernanceCompensationTaskResponse(
        Long id, String batchId, Long itemId, GovernanceCompensationAction action,
        GovernanceCompensationStatus status, String objectPath, int attempts,
        String lastError, LocalDateTime nextAttemptAt, LocalDateTime createdAt,
        LocalDateTime updatedAt, LocalDateTime finishedAt) {
}
