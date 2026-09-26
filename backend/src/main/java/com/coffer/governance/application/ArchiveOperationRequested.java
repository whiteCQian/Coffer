package com.coffer.governance.application;

public record ArchiveOperationRequested(Long ownerId, String batchId) implements com.coffer.auth.service.OwnedWork {
    public ArchiveOperationRequested(String batchId) {
        this(com.coffer.auth.service.TenantContext.requireOwnerId(), batchId);
    }
}
