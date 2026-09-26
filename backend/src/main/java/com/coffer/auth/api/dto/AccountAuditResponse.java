package com.coffer.auth.api.dto;

import com.coffer.auth.domain.AccountAuditLog;

import java.time.LocalDateTime;

public record AccountAuditResponse(Long id, Long actorUserId, String actorUsername,
                                   Long targetUserId, String action, LocalDateTime createdAt) {
    public static AccountAuditResponse from(AccountAuditLog event) {
        return new AccountAuditResponse(event.getId(), event.getActorUserId(), event.getActorUsername(),
                event.getTargetUserId(), event.getAction(), event.getCreatedAt());
    }
}
