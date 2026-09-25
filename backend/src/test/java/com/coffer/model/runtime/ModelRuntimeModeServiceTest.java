package com.coffer.model.runtime;

import com.coffer.entity.ModelRuntimeSetting;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.repository.ModelRuntimeSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelRuntimeModeServiceTest {

    private ModelRuntimeSettingRepository repository;
    private ModelRuntimeProviderFactory providerFactory;
    private ModelRuntimeModeService service;
    private ModelRuntimeSetting setting;

    @BeforeEach
    void setUp() {
        repository = mock(ModelRuntimeSettingRepository.class);
        providerFactory = mock(ModelRuntimeProviderFactory.class);
        setting = ModelRuntimeSetting.builder()
                .id(ModelRuntimeSetting.SINGLETON_ID)
                .activeMode(GovernanceRunMode.API)
                .updatedAt(LocalDateTime.now())
                .build();
        when(repository.findById(ModelRuntimeSetting.SINGLETON_ID)).thenReturn(Optional.of(setting));
        when(repository.save(any(ModelRuntimeSetting.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new ModelRuntimeModeService(repository, providerFactory);
    }

    @Test
    void refusesSwitchUntilTargetModeIsValidated() {
        assertThatThrownBy(() -> service.switchMode(GovernanceRunMode.LOCAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尚未通过");
        assertThat(setting.getActiveMode()).isEqualTo(GovernanceRunMode.API);
    }

    @Test
    void activatesOnlyTheValidatedTargetMode() {
        when(providerFactory.validate(GovernanceRunMode.LOCAL))
                .thenReturn(new ModelRuntimeValidationResult(true, Map.of("chat", "OK"), "通过"));

        service.validate(GovernanceRunMode.LOCAL);
        service.switchMode(GovernanceRunMode.LOCAL);

        assertThat(setting.getActiveMode()).isEqualTo(GovernanceRunMode.LOCAL);
        assertThat(service.requireActiveMode()).isEqualTo(GovernanceRunMode.LOCAL);
        verify(repository, org.mockito.Mockito.times(2)).save(setting);
    }

    @Test
    void failedValidationDoesNotOpenModeGate() {
        when(providerFactory.validate(GovernanceRunMode.API))
                .thenReturn(new ModelRuntimeValidationResult(false,
                        Map.of("chat", "连接失败"), "失败"));

        var response = service.validate(GovernanceRunMode.API);

        assertThat(response.getValidationSuccess()).isFalse();
        assertThat(setting.getApiValidatedAt()).isNull();
        assertThatThrownBy(service::requireActiveMode)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尚未通过");
    }
}
