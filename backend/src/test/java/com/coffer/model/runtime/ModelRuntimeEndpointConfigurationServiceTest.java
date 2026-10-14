package com.coffer.model.runtime;

import com.coffer.config.EmbeddingProperties;
import com.coffer.config.ModelRuntimeProperties;
import com.coffer.entity.ModelProvider;
import com.coffer.entity.ModelRuntimeEndpoint;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.model.runtime.api.ModelRuntimeEndpointRequest;
import com.coffer.repository.ModelRuntimeEndpointRepository;
import com.coffer.service.ModelCredentialService;
import com.coffer.service.SecretCryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ModelRuntimeEndpointConfigurationServiceTest {
    private ModelRuntimeEndpointRepository repository;
    private ModelCredentialService credentials;
    private SecretCryptoService crypto;
    private ModelRuntimeEndpointConfigurationService service;

    @BeforeEach
    void setUp() {
        repository = mock(ModelRuntimeEndpointRepository.class);
        credentials = mock(ModelCredentialService.class);
        crypto = mock(SecretCryptoService.class);
        var embedding = new EmbeddingProperties();
        embedding.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        embedding.setModelName("text-embedding-v3");
        service = new ModelRuntimeEndpointConfigurationService(repository,
                new ModelRuntimeProperties(), embedding, credentials,
                crypto, mock(ApplicationEventPublisher.class), new MockEnvironment());
        ReflectionTestUtils.setField(service, "allowedApiHosts", "api.deepseek.com,dashscope.aliyuncs.com,maas.qianwenaiapi.com");
        ReflectionTestUtils.setField(service, "allowedLocalHosts", "");
        ReflectionTestUtils.setField(service, "allowedLocalPorts", "11434,1234,8000");
        when(repository.findByRunModeAndCapability(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void qianwenVisionCanBeSavedAndTestedWithoutCallingTheProvider() {
        var request = new ModelRuntimeEndpointRequest();
        request.setMode(GovernanceRunMode.API); request.setCapability(ModelRuntimeCapability.VISION);
        request.setBaseUrl("https://maas.qianwenaiapi.com/compatible-mode/v1");
        request.setModelName("qwen-vl-ocr"); request.setApiKey("synthetic-key");
        when(repository.save(any())).thenAnswer(call -> {
            when(repository.findByRunModeAndCapability(GovernanceRunMode.API, ModelRuntimeCapability.VISION))
                    .thenReturn(Optional.of(call.getArgument(0)));
            return call.getArgument(0);
        });
        assertThat(service.save(request).getBaseUrl()).isEqualTo(request.getBaseUrl());
        assertThat(service.resolveForTest(request).baseUrl()).isEqualTo(request.getBaseUrl());
        verify(credentials).save(ModelProvider.QWEN_VL, "synthetic-key");
    }

    @Test
    void qianwenNativePathsAndUnapprovedHostsAreRejectedBeforeSavingCredentials() {
        var request = new ModelRuntimeEndpointRequest();
        request.setMode(GovernanceRunMode.API); request.setCapability(ModelRuntimeCapability.VISION);
        request.setModelName("qwen-vl-ocr"); request.setApiKey("synthetic-key");
        for (String url : java.util.List.of("https://maas.qianwenaiapi.com/api/v1",
                "https://maas.qianwenapi.com/compatible-mode/v1",
                "https://maas.qianwenaiapi.com.evil.example/compatible-mode/v1",
                "https://maas.qianwenaiapi.com@127.0.0.1/v1",
                "http://maas.qianwenaiapi.com/v1", "https://maas.qianwenaiapi.com:8443/v1")) {
            request.setBaseUrl(url);
            assertThatThrownBy(() -> service.save(request)).isInstanceOf(ModelEndpointValidationException.class);
        }
        verify(repository, never()).save(any()); verify(credentials, never()).save(any(), any());
    }

    @Test
    void disabledLocalModelsDoNotHideUserManagedApiSettings() {
        when(credentials.getApiKey(ModelProvider.DEEPSEEK)).thenReturn("sk-user-owned-secret");
        var configs = service.list();
        assertThat(configs).hasSize(4);
        assertThat(configs.stream().filter(c -> c.getMode() == GovernanceRunMode.API))
                .extracting(c -> c.getCapability())
                .containsExactly(ModelRuntimeCapability.CHAT, ModelRuntimeCapability.VISION);
        var chat = configs.stream().filter(c -> c.getMode() == GovernanceRunMode.API
                && c.getCapability() == ModelRuntimeCapability.CHAT).findFirst().orElseThrow();
        assertThat(chat.getBaseUrl()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(chat.getMaskedApiKey()).startsWith("sk-").endsWith("cret").contains("*");
        assertThat(chat.getMaskedApiKey()).doesNotContain("user-owned");
        assertThatThrownBy(() -> service.resolve(GovernanceRunMode.LOCAL, ModelRuntimeCapability.CHAT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("允许列表");
    }

    @Test
    void disallowedSavedEndpointRemainsEditableButCannotBeUsed() {
        var endpoint = ModelRuntimeEndpoint.builder().runMode(GovernanceRunMode.API)
                .capability(ModelRuntimeCapability.CHAT).baseUrl("https://retired.example/v1")
                .modelName("old-model").build();
        when(repository.findByRunModeAndCapability(GovernanceRunMode.API, ModelRuntimeCapability.CHAT))
                .thenReturn(Optional.of(endpoint));
        assertThat(service.list().stream().filter(c -> c.getMode() == GovernanceRunMode.API
                && c.getCapability() == ModelRuntimeCapability.CHAT).findFirst().orElseThrow().getBaseUrl())
                .isEqualTo("https://retired.example/v1");
        assertThatThrownBy(() -> service.resolve(GovernanceRunMode.API, ModelRuntimeCapability.CHAT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("已批准");
    }

    @Test
    void savingDisallowedLocalEndpointStillFailsBeforeWriting() {
        var request = new ModelRuntimeEndpointRequest();
        request.setMode(GovernanceRunMode.LOCAL);
        request.setCapability(ModelRuntimeCapability.CHAT);
        request.setBaseUrl("http://127.0.0.1:11434/v1");
        request.setModelName("local-model");
        assertThatThrownBy(() -> service.save(request)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("允许列表");
        verify(repository, never()).save(any());
    }

    @Test
    void ordinaryUsersCannotSaveDeleteOrTestEmbeddingEvenWithLegacyOverrides() {
        var legacy = ModelRuntimeEndpoint.builder().runMode(GovernanceRunMode.API).capability(ModelRuntimeCapability.EMBEDDING)
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1").modelName("old-vector").encryptedApiKey("old-private-key").useChatCredentials(true).build();
        when(repository.findByRunModeAndCapability(GovernanceRunMode.API, ModelRuntimeCapability.EMBEDDING)).thenReturn(Optional.of(legacy));
        var request = new ModelRuntimeEndpointRequest(); request.setMode(GovernanceRunMode.API); request.setCapability(ModelRuntimeCapability.EMBEDDING);
        assertThatThrownBy(() -> service.save(request)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> service.delete(GovernanceRunMode.API, ModelRuntimeCapability.EMBEDDING)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> service.resolveForTest(request)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(repository, never()).save(any()); verify(credentials, never()).save(any(), any()); verify(crypto, never()).decrypt("old-private-key");
        assertThat(service.list()).hasSize(4);
    }
    @Test
    void embeddingExecutionUsesOnlyAdminMetadataAndNeverReadsLegacyOwnerKey() {
        var admin = mock(AdminEmbeddingService.class); ReflectionTestUtils.setField(service,"adminEmbedding",admin);
        var metadata = new ResolvedModelRuntimeEndpoint(GovernanceRunMode.API,ModelRuntimeCapability.EMBEDDING,"https://dashscope.aliyuncs.com/compatible-mode/v1","admin-vector","","ADMIN:v",true,null);
        when(admin.metadata(GovernanceRunMode.API)).thenReturn(metadata);
        assertThat(service.resolve(GovernanceRunMode.API, ModelRuntimeCapability.EMBEDDING)).isEqualTo(metadata);
        verify(repository, never()).findByRunModeAndCapability(GovernanceRunMode.API, ModelRuntimeCapability.EMBEDDING);
        verify(credentials, never()).getApiKey(any());
    }
}
