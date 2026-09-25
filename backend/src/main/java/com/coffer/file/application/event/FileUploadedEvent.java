package com.coffer.file.application.event;

/** Published inside the upload transaction and handled after that transaction commits. */
public record FileUploadedEvent(String taskId) {
}
