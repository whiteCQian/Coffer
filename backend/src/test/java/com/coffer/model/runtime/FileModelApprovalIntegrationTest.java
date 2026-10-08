package com.coffer.model.runtime;

import com.coffer.auth.domain.AppUser;
import com.coffer.auth.domain.AuthRole;
import com.coffer.auth.infrastructure.AppUserRepository;
import com.coffer.auth.service.TenantContext;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.service.MinioStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:file_model_approval_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.flyway.locations=classpath:db/migration/h2",
        "minio.access-key=test-access-key", "minio.secret-key=test-secret-key"})
class FileModelApprovalIntegrationTest extends com.coffer.auth.OwnerTestSupport {
    private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    @Autowired FileMetadataRepository files;
    @Autowired FileModelApprovalRepository approvals;
    @Autowired ModelContentGate gate;
    @Autowired AppUserRepository users;
    @MockitoBean MinioStorageService storage;
    @MockitoBean ModelExecutionSnapshotService snapshots;

    @Test void approvalIsAuditedWithoutBodyAndDoesNotCrossOwnersOrVersions() {
        Long owner = TenantContext.requireOwnerId();
        FileMetadata file = files.saveAndFlush(FileMetadata.builder().fileName("工资单.png")
                .fileType("png").fileSize(8L).storagePath(ownerPath("files/payroll.png"))
                .contentSha256(SHA).revision(4L).build());
        when(storage.stat(file.getStoragePath())).thenReturn(new FileStoragePort.StoredObject(
                file.getStoragePath(), 8L, SHA, "etag"));
        when(snapshots.preview()).thenReturn(new ModelExecutionSnapshotService.TargetPreview(
                "v1", GovernanceRunMode.API, List.of(new ModelExecutionSnapshotService.Target(
                "VISION", "https://vision.example/api", "vision-model"))));

        ModelExecutionContext.with(snapshot(owner, "v1"), () -> {
            assertThatThrownBy(() -> gate.requireAllowed(file, null, false, ModelRuntimeCapability.VISION))
                    .isInstanceOf(ModelConsentRequiredException.class);
            assertThatThrownBy(() -> gate.approve(file.getId(), ModelRuntimeCapability.VISION,
                    3L, SHA, "SENSITIVE"))
                    .isInstanceOf(com.coffer.file.storage.StorageConflictException.class);
            assertThatThrownBy(() -> gate.approve(file.getId(), ModelRuntimeCapability.VISION,
                    4L, SHA, "CLEAR"))
                    .isInstanceOf(com.coffer.file.storage.StorageConflictException.class);
            var reviewed = gate.approve(file.getId(), ModelRuntimeCapability.VISION, 4L, SHA, "SENSITIVE");
            assertThat(reviewed.risk()).isEqualTo("SENSITIVE");
            gate.requireAllowed(file, null, false, ModelRuntimeCapability.VISION);
        });
        var audit = approvals.findAll();
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).getEndpointUrl()).isEqualTo("https://vision.example/api");
        assertThat(audit.get(0).getContentSha256()).isEqualTo(SHA);
        assertThat(audit.get(0).getModelName()).isEqualTo("vision-model");

        ModelExecutionContext.with(snapshot(owner, "v2"), () ->
                assertThatThrownBy(() -> gate.requireAllowed(file, null, false, ModelRuntimeCapability.VISION))
                        .isInstanceOf(ModelConsentRequiredException.class));
        AppUser other = users.saveAndFlush(new AppUser("other-" + UUID.randomUUID(), "x", AuthRole.USER));
        TenantContext.runAs(other.getId(), () -> ModelExecutionContext.with(snapshot(other.getId(), "v1"), () ->
                assertThatThrownBy(() -> gate.requireAllowed(file, null, false, ModelRuntimeCapability.VISION))
                        .isInstanceOf(com.coffer.auth.service.ResourceNotFoundException.class)));
    }

    private ModelExecutionContext.Snapshot snapshot(Long owner, String version) {
        return new ModelExecutionContext.Snapshot("snapshot", owner, version, GovernanceRunMode.API,
                Map.of(ModelRuntimeCapability.VISION,
                        new ResolvedModelRuntimeEndpoint(GovernanceRunMode.API, ModelRuntimeCapability.VISION,
                                "https://vision.example/api", "vision-model", null, "test", true, null)));
    }
}
