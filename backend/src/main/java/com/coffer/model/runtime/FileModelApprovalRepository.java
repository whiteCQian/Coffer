package com.coffer.model.runtime;

import com.coffer.auth.infrastructure.OwnedRepository;
import java.util.Optional;

public interface FileModelApprovalRepository extends OwnedRepository<FileModelApproval, String> {
    Optional<FileModelApproval> findFirstByFileIdAndFileRevisionAndContentSha256AndConfigurationVersionAndCapabilityOrderByConfirmedAtDesc(
            Long fileId, Long revision, String sha256, String version, ModelRuntimeCapability capability);
}
