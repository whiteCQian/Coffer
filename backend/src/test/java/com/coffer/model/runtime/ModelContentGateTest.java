package com.coffer.model.runtime;

import com.coffer.auth.service.TenantContext;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.file.storage.StorageConflictException;
import com.coffer.governance.domain.GovernanceRunMode;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModelContentGateTest {
    private final FileModelApprovalRepository approvals = mock(FileModelApprovalRepository.class);
    private final FileStoragePort storage = mock(FileStoragePort.class);
    private final com.coffer.file.application.parse.ParsedDocumentStore parsedStore =
            mock(com.coffer.file.application.parse.ParsedDocumentStore.class);
    private final ModelContentGate gate = new ModelContentGate(new SensitiveContentClassifier(), approvals,
            mock(com.coffer.file.infrastructure.persistence.FileMetadataRepository.class), storage,
            mock(com.coffer.file.application.parse.DocumentParseService.class),
            parsedStore,
            mock(ModelExecutionSnapshotService.class));
    private final FileMetadata file = FileMetadata.builder().id(8L).fileName("身份证.txt")
            .fileSize(8L).revision(4L).storagePath("users/1/files/a.txt")
            .contentSha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").build();

    @Test void apiModeBlocksUntilSameFileRevisionHashAndEndpointAreApproved() {
        when(storage.stat(file.getStoragePath())).thenReturn(new FileStoragePort.StoredObject(
                file.getStoragePath(), 8, file.getContentSha256(), "etag"));
        TenantContext.runAs(1L, () -> ModelExecutionContext.with(snapshot("v1", GovernanceRunMode.API), () -> {
            assertThatThrownBy(() -> gate.requireAllowed(file, "身份证号 123", true, ModelRuntimeCapability.CHAT))
                    .isInstanceOf(ModelConsentRequiredException.class);
            when(approvals.findFirstByFileIdAndFileRevisionAndContentSha256AndConfigurationVersionAndCapabilityOrderByConfirmedAtDesc(
                    8L, 4L, file.getContentSha256(), "v1", ModelRuntimeCapability.CHAT))
                    .thenReturn(Optional.of(FileModelApproval.builder().endpointUrl("https://model.example/api")
                            .modelName("chat-model").build()));
            gate.requireAllowed(file, "身份证号 123", true, ModelRuntimeCapability.CHAT);
        }));
        TenantContext.runAs(1L, () -> ModelExecutionContext.with(snapshot("v2", GovernanceRunMode.API), () ->
                assertThatThrownBy(() -> gate.requireAllowed(file, "身份证号 123", true, ModelRuntimeCapability.CHAT))
                        .isInstanceOf(ModelConsentRequiredException.class)));
    }

    @Test void unknownImageRequiresApprovalButLocalEndpointDoesNot() {
        when(storage.stat(file.getStoragePath())).thenReturn(new FileStoragePort.StoredObject(
                file.getStoragePath(), 8, file.getContentSha256(), "etag"));
        TenantContext.runAs(1L, () -> ModelExecutionContext.with(snapshot("v1", GovernanceRunMode.API), () ->
                assertThatThrownBy(() -> gate.requireAllowed(file, null, false, ModelRuntimeCapability.VISION))
                        .isInstanceOf(ModelConsentRequiredException.class)));
        TenantContext.runAs(1L, () -> ModelExecutionContext.with(snapshot("local", GovernanceRunMode.LOCAL), () ->
                gate.requireAllowed(file, null, false, ModelRuntimeCapability.VISION)));
    }

    @Test void searchHitUsesParsedBodyRiskInsteadOfAnEmptySafePlaceholder() {
        FileMetadata neutralName = FileMetadata.builder().id(9L).fileName("notes.txt")
                .fileSize(8L).revision(2L).storagePath("users/1/files/notes.txt")
                .contentSha256("b".repeat(64)).build();
        when(storage.stat(neutralName.getStoragePath())).thenReturn(new FileStoragePort.StoredObject(
                neutralName.getStoragePath(), 8, neutralName.getContentSha256(), "etag"));
        when(parsedStore.load(neutralName)).thenReturn(new com.coffer.file.domain.parse.ParsedDocument(
                9L, 2L, "txt", "test", com.coffer.file.domain.parse.ParseStatus.SUCCESS, null,
                java.util.List.of(new com.coffer.file.domain.parse.ParsedDocument.Chunk(
                        "password: secret123", "LINE", 1, 1, 1, 19))));
        TenantContext.runAs(1L, () -> ModelExecutionContext.with(snapshot("v1", GovernanceRunMode.API), () ->
                assertThatThrownBy(() -> gate.requireFileAllowed(neutralName, ModelRuntimeCapability.CHAT))
                        .isInstanceOf(ModelConsentRequiredException.class)));
        verify(approvals).findFirstByFileIdAndFileRevisionAndContentSha256AndConfigurationVersionAndCapabilityOrderByConfirmedAtDesc(
                9L, 2L, neutralName.getContentSha256(), "v1", ModelRuntimeCapability.CHAT);
    }

    @Test void changedSourceCannotBeClassifiedAsUnknownForRemoteApproval() {
        FileMetadata source = FileMetadata.builder().id(10L).fileName("notes.txt")
                .fileSize(8L).revision(1L).storagePath("users/1/files/source.txt")
                .contentSha256("c".repeat(64)).build();
        var observed = new FileStoragePort.StoredObject(source.getStoragePath(), 8,
                source.getContentSha256(), "etag");
        when(storage.stat(source.getStoragePath())).thenReturn(observed);
        when(storage.readIfUnchanged(source.getStoragePath(), observed))
                .thenThrow(new StorageConflictException("对象在校验后已变化"));
        TenantContext.runAs(1L, () -> ModelExecutionContext.with(snapshot("v1", GovernanceRunMode.API), () ->
                assertThatThrownBy(() -> gate.requireFileAllowed(source, ModelRuntimeCapability.CHAT))
                        .isInstanceOf(StorageConflictException.class)));
        verifyNoInteractions(approvals);
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
