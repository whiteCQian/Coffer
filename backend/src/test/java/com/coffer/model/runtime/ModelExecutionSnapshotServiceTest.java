package com.coffer.model.runtime;

import com.coffer.auth.service.OwnerAuthorization;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.model.runtime.api.ModelRuntimeModeStatusResponse;
import com.coffer.repository.ModelExecutionSnapshotRepository;
import com.coffer.repository.ModelRuntimeSettingRepository;
import com.coffer.service.SecretCryptoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelExecutionSnapshotServiceTest {
    @Test
    void disabledEmbeddingWithNoEndpointDoesNotBlockImportTargetOrSnapshot() {
        var owner = mock(OwnerAuthorization.class);
        var modes = mock(ModelRuntimeModeService.class);
        var configuration = mock(ModelRuntimeEndpointConfigurationService.class);
        var snapshots = mock(ModelExecutionSnapshotRepository.class);
        var service = new ModelExecutionSnapshotService(owner, modes, configuration,
                snapshots, mock(SecretCryptoService.class), new ObjectMapper(),
                mock(ModelRuntimeSettingRepository.class));
        when(owner.requireOwner()).thenReturn(1L);
        when(modes.requireActiveMode()).thenReturn(GovernanceRunMode.API);
        when(configuration.embeddingAvailable(GovernanceRunMode.API)).thenReturn(false);
        when(configuration.resolve(GovernanceRunMode.API, ModelRuntimeCapability.CHAT))
                .thenReturn(endpoint(ModelRuntimeCapability.CHAT, "https://chat.example/v1"));
        when(configuration.resolve(GovernanceRunMode.API, ModelRuntimeCapability.VISION))
                .thenReturn(endpoint(ModelRuntimeCapability.VISION, "https://vision.example/v1"));
        when(configuration.resolve(GovernanceRunMode.API, ModelRuntimeCapability.EMBEDDING))
                .thenThrow(new IllegalArgumentException("Base URL不能为空"));

        var target = service.preview();
        assertThat(target.targets()).extracting(ModelExecutionSnapshotService.Target::capability)
                .containsExactly("CHAT", "VISION");
        var snapshot = service.capture(target.configurationVersion(), true, "INBOX_IMPORT");
        assertThat(snapshot.endpoints()).containsOnlyKeys(ModelRuntimeCapability.CHAT, ModelRuntimeCapability.VISION);
        verify(configuration, never()).resolve(GovernanceRunMode.API, ModelRuntimeCapability.EMBEDDING);
        verify(snapshots).save(any());
    }

    @Test
    void queuedSnapshotStopsBeingUsableAfterDestinationChanges() {
        var owner = mock(OwnerAuthorization.class);
        var modes = mock(ModelRuntimeModeService.class);
        var configuration = mock(ModelRuntimeEndpointConfigurationService.class);
        var service = new ModelExecutionSnapshotService(owner, modes, configuration,
                mock(ModelExecutionSnapshotRepository.class), mock(SecretCryptoService.class),
                new ObjectMapper(), mock(ModelRuntimeSettingRepository.class));
        when(owner.requireOwner()).thenReturn(1L);
        when(modes.requireActiveMode()).thenReturn(GovernanceRunMode.API);
        when(modes.status()).thenReturn(ModelRuntimeModeStatusResponse.builder()
                .activeMode(GovernanceRunMode.API).currentModeValidated(true).build());
        when(configuration.resolve(eq(GovernanceRunMode.API), any()))
                .thenAnswer(invocation -> endpoint(invocation.getArgument(1), "https://old.example/v1"));

        var initial = service.preview();
        var snapshot = new ModelExecutionContext.Snapshot("queued", 1L,
                initial.configurationVersion(), GovernanceRunMode.API,
                Map.of(ModelRuntimeCapability.CHAT,
                        endpoint(ModelRuntimeCapability.CHAT, "https://old.example/v1")));
        service.requireCurrent(snapshot);

        when(configuration.resolve(eq(GovernanceRunMode.API), eq(ModelRuntimeCapability.CHAT)))
                .thenReturn(endpoint(ModelRuntimeCapability.CHAT, "https://new.example/v1"));
        assertThatThrownBy(() -> service.requireCurrent(snapshot))
                .isInstanceOf(ModelConsentRequiredException.class);
    }

    private ResolvedModelRuntimeEndpoint endpoint(ModelRuntimeCapability capability, String url) {
        return new ResolvedModelRuntimeEndpoint(GovernanceRunMode.API, capability,
                url, "test-model", null, "test", true, null);
    }
}
