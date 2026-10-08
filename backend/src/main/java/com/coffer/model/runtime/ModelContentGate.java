package com.coffer.model.runtime;

import com.coffer.auth.service.ResourceNotFoundException;
import com.coffer.file.application.parse.DocumentParseService;
import com.coffer.file.application.parse.ParsedDocumentStore;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.parse.ParseStatus;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.governance.domain.GovernanceRunMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** Fail-closed, per-file authorization checked immediately before remote file content submission. */
@Service @RequiredArgsConstructor @com.coffer.auth.service.OwnerOnly
public class ModelContentGate {
    private final SensitiveContentClassifier classifier;
    private final FileModelApprovalRepository approvals;
    private final FileMetadataRepository files;
    private final FileStoragePort storage;
    private final DocumentParseService parser;
    private final ParsedDocumentStore parsedStore;
    private final ModelExecutionSnapshotService snapshots;

    public record Assessment(Long fileId, Long revision, String contentSha256, String risk,
                             String configurationVersion, GovernanceRunMode mode,
                             ModelRuntimeCapability capability, String endpointUrl, String modelName) { }

    public void requireAllowed(FileMetadata file, String text, boolean locallyParsed,
                               ModelRuntimeCapability capability) {
        requireRiskAllowed(file, classifier.classify(file, text, locallyParsed), capability);
    }

    /** Search metadata is about a file too: classify its locally parsed source before exposing it to chat. */
    public void requireFileAllowed(FileMetadata file, ModelRuntimeCapability capability) {
        requireRiskAllowed(file, classifySource(file), capability);
    }

    private void requireRiskAllowed(FileMetadata file, SensitiveContentClassifier.Risk risk,
                                    ModelRuntimeCapability capability) {
        var target = ModelExecutionContext.require();
        snapshots.requireCurrent(target);
        if (!target.endpoints().containsKey(capability)) throw new ModelConsentRequiredException();
        verifyObject(file);
        if (target.mode() == GovernanceRunMode.LOCAL) return;
        if (risk == SensitiveContentClassifier.Risk.CLEAR) return;
        if (file.getContentSha256() == null || file.getId() == null || file.getRevision() == null)
            throw new ModelConsentRequiredException();
        var endpoint = target.endpoints().get(capability);
        if (endpoint == null || approvals
                .findFirstByFileIdAndFileRevisionAndContentSha256AndConfigurationVersionAndCapabilityOrderByConfirmedAtDesc(
                        file.getId(), file.getRevision(), file.getContentSha256(), target.version(), capability)
                .filter(row -> Objects.equals(row.getEndpointUrl(), endpoint.baseUrl())
                        && Objects.equals(row.getModelName(), endpoint.modelName()))
                .isEmpty()) throw new ModelConsentRequiredException();
    }

    /** Local assessment returns risk labels and destination only; never returns document content. */
    public Assessment assess(Long fileId, ModelRuntimeCapability capability) {
        FileMetadata file = files.findById(fileId).orElseThrow(ResourceNotFoundException::new);
        var preview = snapshots.preview();
        var target = preview.targets().stream().filter(t -> t.capability().equals(capability.name()))
                .findFirst().orElseThrow(ModelConsentRequiredException::new);
        verifyObject(file);
        SensitiveContentClassifier.Risk risk = classifySource(file);
        return new Assessment(file.getId(), file.getRevision(), file.getContentSha256(),
                risk.name(), preview.configurationVersion(), preview.mode(), capability,
                target.baseUrl(), target.modelName());
    }

    @Transactional
    public Assessment approve(Long fileId, ModelRuntimeCapability capability,
                              long reviewedRevision, String reviewedSha256, String reviewedRisk) {
        var target = ModelExecutionContext.require();
        Assessment assessment = assess(fileId, capability);
        if (!assessment.configurationVersion().equals(target.version())) throw new ModelConsentRequiredException();
        if (assessment.revision() == null || assessment.revision() != reviewedRevision
                || reviewedSha256 == null || !reviewedSha256.equals(assessment.contentSha256())
                || reviewedRisk == null || !reviewedRisk.equals(assessment.risk()))
            throw new com.coffer.file.storage.StorageConflictException("文件版本已变化，请重新查看授权目标");
        if (target.mode() == GovernanceRunMode.API) {
            approvals.save(FileModelApproval.builder().id(UUID.randomUUID().toString()).fileId(fileId)
                    .fileRevision(assessment.revision()).contentSha256(assessment.contentSha256())
                    .configurationVersion(target.version()).capability(capability)
                    .endpointUrl(assessment.endpointUrl()).modelName(assessment.modelName())
                    .risk(assessment.risk()).confirmedAt(LocalDateTime.now()).build());
        }
        return assessment;
    }

    private FileStoragePort.StoredObject verifyObject(FileMetadata file) {
        var current = com.coffer.file.application.VerifiedFileSource.statBounded(storage, file.getStoragePath());
        if (current.sha256() == null || !current.sha256().matches("[0-9a-f]{64}")
                || !Objects.equals(file.getFileSize(), current.size()))
            throw new com.coffer.file.storage.StorageConflictException("文件正文与元数据不一致");
        if (file.getContentSha256() == null || file.getContentSha256().isBlank()) {
            // Legacy rows predate SHA-256. Anchor them to the physical bytes before
            // any approval or remote send; all subsequent checks use this identity.
            file.setContentSha256(current.sha256());
            files.saveAndFlush(file);
        } else if (!file.getContentSha256().equals(current.sha256()))
            throw new com.coffer.file.storage.StorageConflictException("文件正文已发生变化");
        return current;
    }

    private SensitiveContentClassifier.Risk classifySource(FileMetadata file) {
        if (isImage(file)) return classifier.classify(file, null, false);
        try {
            var parsed = parsedStore.load(file);
            if (parsed == null) {
                verifyObject(file);
                try (InputStream input = com.coffer.file.application.VerifiedFileSource.open(storage, file)) {
                    parsed = parser.parseStructured(file, input);
                }
            }
            boolean readable = parsed.status() == ParseStatus.SUCCESS
                    || parsed.status() == ParseStatus.EMPTY_CONTENT;
            return classifier.classify(file, parsed.content(), readable);
        } catch (com.coffer.file.storage.StorageConflictException
                 | com.coffer.file.storage.StorageObjectNotFoundException changed) {
            throw changed;
        } catch (Exception unreadable) {
            return classifier.classify(file, null, false);
        }
    }

    private static boolean isImage(FileMetadata file) {
        return java.util.Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp")
                .contains(file.getFileType() == null ? "" : file.getFileType().toLowerCase(java.util.Locale.ROOT));
    }
}
