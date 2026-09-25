package com.coffer.model.runtime;

import com.coffer.entity.ModelRuntimeSetting;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.repository.ModelRuntimeSettingRepository;
import com.coffer.model.runtime.api.ModelRuntimeModeStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.event.EventListener;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Owns the singleton active mode, validation gate, and task mode snapshots. */
@Service
@RequiredArgsConstructor
public class ModelRuntimeModeService {

    private final ModelRuntimeSettingRepository repository;
    private final ModelRuntimeProviderFactory providerFactory;

    @Transactional(readOnly = true)
    public ModelRuntimeModeStatusResponse status() {
        return toResponse(requireSetting(), null, null, Map.of());
    }

    @Transactional
    public ModelRuntimeModeStatusResponse validate(GovernanceRunMode mode) {
        GovernanceRunMode target = requireMode(mode);
        ModelRuntimeValidationResult result = providerFactory.validate(target);
        ModelRuntimeSetting setting = requireSetting();
        if (result.success()) {
            setting.markValidated(target, LocalDateTime.now());
        } else if (target == GovernanceRunMode.LOCAL) {
            setting.setLocalValidatedAt(null);
        } else {
            setting.setApiValidatedAt(null);
        }
        setting.setUpdatedAt(LocalDateTime.now());
        repository.save(setting);
        return toResponse(setting, result.success(), result.message(), result.capabilities());
    }

    @Transactional
    public ModelRuntimeModeStatusResponse switchMode(GovernanceRunMode mode) {
        GovernanceRunMode target = requireMode(mode);
        ModelRuntimeSetting setting = requireSetting();
        if (!isValidated(setting, target)) {
            throw new IllegalArgumentException("目标运行模式尚未通过模型连通性校验，请先验证 " + target.name() + " 模式");
        }
        setting.setActiveMode(target);
        setting.setUpdatedAt(LocalDateTime.now());
        return toResponse(repository.save(setting), true, "运行模式切换成功", Map.of());
    }

    /** Returns the current validated mode for a new model task. */
    @Transactional(readOnly = true)
    public GovernanceRunMode requireActiveMode() {
        ModelRuntimeSetting setting = requireSetting();
        GovernanceRunMode mode = setting.getActiveMode() == null ? GovernanceRunMode.API : setting.getActiveMode();
        if (!isValidated(setting, mode)) {
            throw new IllegalArgumentException("当前运行模式尚未通过模型连通性校验，请先验证 " + mode.name() + " 模式");
        }
        return mode;
    }

    /** Resolves a request mode while enforcing the singleton global mode. */
    @Transactional(readOnly = true)
    public GovernanceRunMode resolveForTask(GovernanceRunMode requestedMode) {
        GovernanceRunMode active = requireActiveMode();
        if (requestedMode != null && requestedMode != active) {
            throw new IllegalArgumentException("请求运行模式与当前全局运行模式不一致: " + active.name());
        }
        return active;
    }

    public GovernanceRunMode providerMode() {
        GovernanceRunMode snapshot = ModelRuntimeModeContext.current();
        if (snapshot != null) {
            return snapshot;
        }
        return requireActiveMode();
    }

    public <T> T withSnapshot(GovernanceRunMode mode, Supplier<T> action) {
        return ModelRuntimeModeContext.withSnapshot(mode, action);
    }

    public void withSnapshot(GovernanceRunMode mode, Runnable action) {
        ModelRuntimeModeContext.withSnapshot(mode, action);
    }

    /** Credential changes invalidate both mode validations until the next connectivity check. */
    @EventListener
    @Transactional
    public void onCredentialChanged(ModelCredentialChangedEvent ignored) {
        clearValidations();
    }

    @EventListener
    @Transactional
    public void onRuntimeConfigurationChanged(ModelRuntimeConfigurationChangedEvent ignored) {
        clearValidations();
    }

    private void clearValidations() {
        ModelRuntimeSetting setting = requireSetting();
        setting.clearValidations();
        setting.setUpdatedAt(LocalDateTime.now());
        repository.save(setting);
    }

    private ModelRuntimeSetting requireSetting() {
        return repository.findById(ModelRuntimeSetting.SINGLETON_ID)
                .orElseGet(() -> repository.save(ModelRuntimeSetting.builder()
                        .id(ModelRuntimeSetting.SINGLETON_ID)
                        .activeMode(GovernanceRunMode.API)
                        .updatedAt(LocalDateTime.now())
                        .build()));
    }

    private boolean isValidated(ModelRuntimeSetting setting, GovernanceRunMode mode) {
        return setting.validatedAt(mode) != null;
    }

    private GovernanceRunMode requireMode(GovernanceRunMode mode) {
        return mode == null ? GovernanceRunMode.API : mode;
    }

    private ModelRuntimeModeStatusResponse toResponse(ModelRuntimeSetting setting,
                                                       Boolean validationSuccess,
                                                       String message,
                                                       Map<String, String> capabilities) {
        Map<String, Boolean> validatedModes = new LinkedHashMap<>();
        Map<String, LocalDateTime> validatedAt = new LinkedHashMap<>();
        for (GovernanceRunMode mode : GovernanceRunMode.values()) {
            validatedModes.put(mode.name(), isValidated(setting, mode));
            validatedAt.put(mode.name(), setting.validatedAt(mode));
        }
        GovernanceRunMode active = setting.getActiveMode() == null
                ? GovernanceRunMode.API : setting.getActiveMode();
        return ModelRuntimeModeStatusResponse.builder()
                .activeMode(active)
                .currentModeValidated(isValidated(setting, active))
                .validatedModes(validatedModes)
                .validatedAt(validatedAt)
                .validationSuccess(validationSuccess)
                .message(message)
                .capabilities(capabilities == null ? Map.of() : new LinkedHashMap<>(capabilities))
                .build();
    }
}
