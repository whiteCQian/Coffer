package com.coffer.file.application.event;

public record FileUploadedEvent(Long ownerId, String taskId) implements com.coffer.auth.service.OwnedWork {
    public FileUploadedEvent(String taskId) {
        this(com.coffer.auth.service.TenantContext.requireOwnerId(), taskId);
    }
}
