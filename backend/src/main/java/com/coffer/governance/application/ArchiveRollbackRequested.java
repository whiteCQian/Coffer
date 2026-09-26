package com.coffer.governance.application;

public record ArchiveRollbackRequested(Long ownerId, String batchId, Long itemId) implements com.coffer.auth.service.OwnedWork {
    public ArchiveRollbackRequested(String batchId, Long itemId) {
        this(com.coffer.auth.service.TenantContext.requireOwnerId(), batchId, itemId);
    }
}
