package com.coffer.vector;

public record FileVectorIndexRequested(Long ownerId, Long fileId) implements com.coffer.auth.service.OwnedWork {
    public FileVectorIndexRequested(Long fileId) {
        this(com.coffer.auth.service.TenantContext.requireOwnerId(), fileId);
    }
}
