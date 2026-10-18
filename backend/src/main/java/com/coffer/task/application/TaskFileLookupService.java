package com.coffer.task.application;
import com.coffer.auth.service.OwnerOnly;
import com.coffer.auth.service.ResourceNotFoundException;
import com.coffer.file.api.dto.FileDetailResponse;
import com.coffer.file.application.FileService;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolve upload receipts by owned task identity, never by ambiguous filenames. */
@OwnerOnly
@Service
public class TaskFileLookupService {
    private final FileMetadataRepository files;
    private final FileService service;
    public TaskFileLookupService(FileMetadataRepository files, FileService service) { this.files = files; this.service = service; }
    @Transactional(readOnly = true)
    public FileDetailResponse find(String taskId) {
        Long owner = com.coffer.auth.service.TenantContext.requireOwnerId();
        var file = files.findByTaskId(taskId).orElseThrow(ResourceNotFoundException::new);
        if (!owner.equals(file.getOwnerId())) throw new ResourceNotFoundException();
        return service.getFileDetail(file.getId());
    }
}
