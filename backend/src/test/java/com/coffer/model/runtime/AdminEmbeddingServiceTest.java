package com.coffer.model.runtime;

import com.coffer.auth.service.AdminAuthorization;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.service.SecretCryptoService;
import com.coffer.model.runtime.api.ModelRuntimeEndpointTestResponse;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminEmbeddingServiceTest {
    AdminEmbeddingSetting row;
    AdminEmbeddingSettingRepository repository;
    AdminAuthorization authorization;
    ModelRuntimeConnectivityTester tester;
    AdminEmbeddingService service;
    static final String KEY = "synthetic-admin-key-never-used-on-network";
    @BeforeEach void setup() {
        row = new AdminEmbeddingSetting(); repository = mock(AdminEmbeddingSettingRepository.class);
        authorization = mock(AdminAuthorization.class); tester = mock(ModelRuntimeConnectivityTester.class);
        var crypto = new SecretCryptoService("unit-test-master", new MockEnvironment()); crypto.initialize();
        when(repository.findById(1L)).thenReturn(Optional.of(row));
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        service = new AdminEmbeddingService(repository,crypto,authorization,tester);
        ReflectionTestUtils.setField(service,"allowedApiHosts","dashscope.aliyuncs.com,maas.qianwenaiapi.com");
        ReflectionTestUtils.setField(service,"allowedLocalHosts","localhost");
        ReflectionTestUtils.setField(service,"allowedLocalPorts","1234");
        when(tester.test(any(),any())).thenReturn(ModelRuntimeEndpointTestResponse.builder().success(true).message("OK").build());
    }
    AdminEmbeddingRequest request() {
        var r = new AdminEmbeddingRequest(); r.setMode(GovernanceRunMode.API);
        r.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1"); r.setModelName("synthetic-vector"); r.setDimensions(1024); r.setApiKey(KEY); return r;
    }
    void ready() { service.save(request()); service.validate(); service.enable(true); }
    @Test void qianwenEmbeddingRequiresCompatiblePathBeforeKeyPersistence() {
        var request = request(); request.setBaseUrl("https://maas.qianwenaiapi.com/api/v1");
        assertThatThrownBy(() -> service.save(request)).isInstanceOf(ModelEndpointValidationException.class)
                .hasMessageContaining("OpenAI 兼容");
        verify(repository, never()).saveAndFlush(any());
        request.setBaseUrl("https://maas.qianwenaiapi.com/compatible-mode/v1");
        assertThat(service.save(request).baseUrl()).isEqualTo(request.getBaseUrl());
        assertThat(row.getEncryptedApiKey()).doesNotContain(KEY);
        verifyNoInteractions(tester);
    }
    @Test void savingEncryptsAndRequiresValidationBeforeEnable() {
        var view=service.save(request()); assertThat(view.maskedApiKey()).doesNotContain(KEY);
        assertThat(row.getEncryptedApiKey()).startsWith("v1:").doesNotContain(KEY);
        assertThat(view.enabled()).isFalse(); assertThatThrownBy(() -> service.enable(true)).isInstanceOf(IllegalArgumentException.class);
        verify(tester,never()).test(any(),any());
        assertThat(service.validate().success()).isTrue(); assertThat(service.enable(true).enabled()).isTrue();
    }
    @Test void adminKeyIsAbsentFromOwnerMetadataAndApprovedSnapshots() {
        ready(); var metadata = service.metadata(GovernanceRunMode.API);
        assertThat(metadata.apiKey()).isEmpty(); assertThat(metadata.source()).startsWith("ADMIN:");
        assertThat(service.status(GovernanceRunMode.API).toString()).doesNotContain(KEY,"maskedApiKey");
        var execution=service.execution(metadata,GovernanceRunMode.API);
        assertThat(execution.endpoint().apiKey()).isEqualTo(KEY); assertThat(execution.dimensions()).isEqualTo(1024);
        assertThat(service.status(GovernanceRunMode.LOCAL).available()).isFalse();
        assertThatThrownBy(() -> service.execution(metadata,GovernanceRunMode.LOCAL)).isInstanceOf(ModelConsentRequiredException.class);
    }
    @Test void changingConfigInvalidatesOldTargetsAndSeparatesIncompatibleIndexes() {
        ready(); var old=service.metadata(GovernanceRunMode.API); var oldIndex=service.indexVersion();
        var r=request(); r.setDimensions(512); service.save(r); service.validate(); service.enable(true);
        assertThat(service.indexVersion()).isNotEqualTo(oldIndex);
        assertThatThrownBy(() -> service.execution(old,GovernanceRunMode.API)).isInstanceOf(ModelConsentRequiredException.class);
        var sameModelIndex=service.indexVersion(); r.setApiKey("rotated-synthetic-key"); service.save(r);
        assertThat(service.indexVersion()).isEqualTo(sameModelIndex);
    }
    @Test void failedConnectivityCannotLeaveTheServiceEnabled() {
        ready(); when(tester.test(any(),any())).thenReturn(ModelRuntimeEndpointTestResponse.builder().success(false).message("failed").build());
        assertThat(service.validate().success()).isFalse(); assertThat(service.status(GovernanceRunMode.API).available()).isFalse();
        assertThat(row.getValidatedAt()).isNull(); assertThatThrownBy(() -> service.enable(true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unauthorizedManagementDoesNotReadOrWriteConfigOrCallModel() {
        doThrow(new org.springframework.security.access.AccessDeniedException("denied")).when(authorization).requireAdmin();
        assertThatThrownBy(() -> service.getAdmin()).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> service.save(request())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> service.validate()).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> service.enable(true)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> service.clear()).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(repository,tester);
    }
}
