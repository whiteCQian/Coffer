package com.coffer.model.runtime;

import com.coffer.auth.service.ResourceNotFoundException;
import com.coffer.auth.service.TenantContext;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.file.storage.StorageConflictException;
import com.coffer.governance.domain.GovernanceRunMode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelContentGateTest {
    private final FileModelApprovalRepository approvals = mock(FileModelApprovalRepository.class);
    private final FileStoragePort storage = mock(FileStoragePort.class);
    private final com.coffer.file.application.parse.ParsedDocumentStore parsedStore =
            mock(com.coffer.file.application.parse.ParsedDocumentStore.class);
    private final ModelExecutionSnapshotService snapshots = mock(ModelExecutionSnapshotService.class);
    private final ModelContentGate gate = new ModelContentGate(new SensitiveContentClassifier(), approvals,
            mock(com.coffer.file.infrastructure.persistence.FileMetadataRepository.class), storage,
            mock(com.coffer.file.application.parse.DocumentParseService.class), parsedStore, snapshots);

    private FileMetadata file(Long owner) {
        var file = FileMetadata.builder().id(8L).fileName("身份证.txt").fileSize(8L).revision(4L)
                .storagePath("users/" + owner + "/files/a.txt").contentSha256("a".repeat(64)).build();
        ReflectionTestUtils.setField(file, "ownerId", owner);
        return file;
    }
    private void stored(FileMetadata file) {
        when(storage.stat(file.getStoragePath())).thenReturn(new FileStoragePort.StoredObject(
                file.getStoragePath(), 8, file.getContentSha256(), "etag"));
    }

    @Test void ownSensitiveAndUnknownChatContentNeedsNoFileApproval() {
        var file = file(1L); stored(file);
        for (String version : new String[]{"v1", "v2"}) {
            TenantContext.runAs(1L, () -> ModelExecutionContext.with(snapshot(version, GovernanceRunMode.API), () -> {
                gate.requireAllowed(file, "身份证号 123 password: secret123", true, ModelRuntimeCapability.CHAT);
                gate.requireAllowed(file, null, false, ModelRuntimeCapability.CHAT);
            }));
        }
        verifyNoInteractions(approvals);
    }

    @Test void chatSearchDoesNotPreparseBodyOrLookForApproval() {
        var file = file(1L); stored(file);
        TenantContext.runAs(1L, () -> ModelExecutionContext.with(snapshot("v1", GovernanceRunMode.API), () ->
                gate.requireFileAllowed(file, ModelRuntimeCapability.CHAT)));
        verifyNoInteractions(approvals, parsedStore);
    }

    @Test void unknownVisionContentStillRequiresApprovalButLocalEndpointDoesNot() {
        var file = file(1L); stored(file);
        TenantContext.runAs(1L, () -> ModelExecutionContext.with(snapshot("v1", GovernanceRunMode.API), () ->
                assertThatThrownBy(() -> gate.requireAllowed(file, null, false, ModelRuntimeCapability.VISION))
                        .isInstanceOf(ModelConsentRequiredException.class)));
        TenantContext.runAs(1L, () -> ModelExecutionContext.with(snapshot("local", GovernanceRunMode.LOCAL), () ->
                gate.requireAllowed(file, null, false, ModelRuntimeCapability.VISION)));
    }

    @Test void changedSourceIsBlockedEvenWithDefaultChatAccess() {
        var file = file(1L);
        when(storage.stat(file.getStoragePath())).thenReturn(new FileStoragePort.StoredObject(
                file.getStoragePath(), 8, "b".repeat(64), "changed"));
        TenantContext.runAs(1L, () -> ModelExecutionContext.with(snapshot("v1", GovernanceRunMode.API), () ->
                assertThatThrownBy(() -> gate.requireFileAllowed(file, ModelRuntimeCapability.CHAT))
                        .isInstanceOf(StorageConflictException.class)));
        verifyNoInteractions(approvals);
    }

    @Test void foreignFileNeverReachesStorageOrApprovalLookup() {
        var foreign = file(2L);
        TenantContext.runAs(1L, () -> ModelExecutionContext.with(snapshot("v1", GovernanceRunMode.API), () -> {
            assertThatThrownBy(() -> gate.requireFileAllowed(foreign, ModelRuntimeCapability.CHAT))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> gate.requireAllowed(foreign, "foreign", true, ModelRuntimeCapability.CHAT))
                    .isInstanceOf(ResourceNotFoundException.class);
        }));
        verifyNoInteractions(storage, approvals, parsedStore, snapshots);
    }

    @Test void staleOrMissingModelTargetStillBlocksChat() {
        var file = file(1L);
        var stale = snapshot("stale", GovernanceRunMode.API);
        doThrow(new ModelConsentRequiredException()).when(snapshots).requireCurrent(stale);
        TenantContext.runAs(1L, () -> {
            assertThatThrownBy(() -> gate.requireFileAllowed(file, ModelRuntimeCapability.CHAT))
                    .isInstanceOf(ModelConsentRequiredException.class);
            ModelExecutionContext.with(stale, () -> assertThatThrownBy(() ->
                    gate.requireFileAllowed(file, ModelRuntimeCapability.CHAT))
                    .isInstanceOf(ModelConsentRequiredException.class));
        });
        verifyNoInteractions(storage, approvals);
    }

    private ModelExecutionContext.Snapshot snapshot(String version, GovernanceRunMode mode) {
        return new ModelExecutionContext.Snapshot("snap", 1L, version, mode, Map.of(
                ModelRuntimeCapability.CHAT,
                new ResolvedModelRuntimeEndpoint(mode, ModelRuntimeCapability.CHAT,
                        "https://model.example/api", "chat-model", null, "test", true, null),
                ModelRuntimeCapability.VISION,
                new ResolvedModelRuntimeEndpoint(mode, ModelRuntimeCapability.VISION,
                        "https://vision.example/api", "vision-model", null, "test", true, null)));
    }
}
