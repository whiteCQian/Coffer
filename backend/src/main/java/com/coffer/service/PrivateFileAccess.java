package com.coffer.service;

import com.coffer.auth.service.OwnerAuthorization;
import com.coffer.auth.service.ResourceNotFoundException;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Objects;

/** Final database gate before a source enters a tool result, memory or citation. */
@Service
@RequiredArgsConstructor
public class PrivateFileAccess {
    private final OwnerAuthorization authorization;
    private final FileMetadataRepository files;
    public FileMetadata require(Long id) {
        Long owner = authorization.requireOwner();
        FileMetadata file = files.findById(id).orElseThrow(ResourceNotFoundException::new);
        if (!owner.equals(file.getOwnerId())) throw new ResourceNotFoundException();
        return file;
    }
    public FileMetadata requireVersion(Long id, Long revision) {
        FileMetadata file = require(id);
        if (revision == null || !Objects.equals(file.getRevision(), revision)) throw new FileVersionConflictException();
        return file;
    }
    public boolean current(Long id, Long revision) {
        try { requireVersion(id, revision); return true; }
        catch (ResourceNotFoundException | FileVersionConflictException ignored) { return false; }
    }
}
