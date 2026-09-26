package com.coffer.model.runtime;

import com.coffer.auth.service.*;
import com.coffer.entity.ModelExecutionSnapshot;
import com.coffer.repository.ModelExecutionSnapshotRepository;
import com.coffer.service.SecretCryptoService;
import com.coffer.governance.domain.GovernanceRunMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import java.nio.charset.StandardCharsets;

@Service @RequiredArgsConstructor
public class ModelExecutionSnapshotService {
    private final OwnerAuthorization authorization;
    private final ModelRuntimeModeService modes;
    private final ModelRuntimeEndpointConfigurationService configuration;
    private final ModelExecutionSnapshotRepository snapshots;
    private final SecretCryptoService crypto;
    private final ObjectMapper json;
    private final com.coffer.repository.ModelRuntimeSettingRepository settings;

    public record Target(String capability, String baseUrl, String modelName) {}
    public record TargetPreview(String configurationVersion, GovernanceRunMode mode, List<Target> targets) {}
    public record Material(GovernanceRunMode mode, List<ResolvedModelRuntimeEndpoint> endpoints) {
        @Override public String toString() { return "ModelConfiguration[redacted]"; }
    }
    private Material material() {
        authorization.requireOwner();
        GovernanceRunMode mode = modes.requireActiveMode();
        return new Material(mode, Arrays.stream(ModelRuntimeCapability.values()).map(c -> configuration.resolve(mode, c)).toList());
    }
    public TargetPreview preview() { return view(material()); }
    private TargetPreview view(Material material) {
        return new TargetPreview(version(material), material.mode(), material.endpoints().stream()
                .map(e -> new Target(e.capability().name(), e.baseUrl(), e.modelName())).toList());
    }
    private String serialize(Material value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ignored) { throw new IllegalStateException("模型配置快照无法创建"); }
    }
    private String version(Material value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(serialize(value).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ignored) { throw new IllegalStateException("模型配置版本无法创建"); }
    }
    @Transactional
    public ModelExecutionContext.Snapshot capture(String expectedVersion, boolean allowSensitive, String purpose) {
        Long owner = authorization.requireOwner();
        Material material = material();
        String version = version(material);
        if (!allowSensitive || expectedVersion == null || !version.equals(expectedVersion)) throw new ModelConsentRequiredException();
        String id = UUID.randomUUID().toString();
        snapshots.save(ModelExecutionSnapshot.builder().id(id).configurationVersion(version).runMode(material.mode().name())
                .encryptedConfiguration(crypto.encrypt(serialize(material))).confirmedAt(LocalDateTime.now()).purpose(purpose).build());
        return context(id, owner, version, material);
    }
    public ModelExecutionContext.Snapshot load(String id) {
        Long owner = authorization.requireOwner();
        if (id == null) throw new ModelConsentRequiredException();
        var row = snapshots.findById(id).orElseThrow(ResourceNotFoundException::new);
        if (!owner.equals(row.getOwnerId())) throw new ResourceNotFoundException();
        try {
            Material material = json.readValue(crypto.decrypt(row.getEncryptedConfiguration()), Material.class);
            return context(id, owner, row.getConfigurationVersion(), material);
        } catch (Exception ignored) { throw new IllegalStateException("模型配置快照无法读取"); }
    }
    public TargetPreview describe(String id) {
        var snapshot = load(id);
        return new TargetPreview(snapshot.version(), snapshot.mode(), snapshot.endpoints().values().stream()
                .map(e -> new Target(e.capability().name(), e.baseUrl(), e.modelName())).toList());
    }
    private ModelExecutionContext.Snapshot context(String id, Long owner, String version, Material material) {
        Map<ModelRuntimeCapability,ResolvedModelRuntimeEndpoint> endpoints = new EnumMap<>(ModelRuntimeCapability.class);
        material.endpoints().forEach(e -> endpoints.put(e.capability(), e));
        return new ModelExecutionContext.Snapshot(id, owner, version, material.mode(), endpoints);
    }
    public <T> T with(String id, java.util.function.Supplier<T> action) { return ModelExecutionContext.with(load(id), action); }
    public void with(String id, Runnable action) { ModelExecutionContext.with(load(id), action); }

    @Transactional
    public void authorizeInbox() {
        Long owner = authorization.requireOwner();
        var snapshot = ModelExecutionContext.require();
        var setting = settings.findById(owner).orElseThrow(ResourceNotFoundException::new);
        setting.setInboxSnapshotId(snapshot.id());
        settings.save(setting);
    }
    @Transactional
    public void revokeInbox() {
        Long owner = authorization.requireOwner();
        settings.findById(owner).ifPresent(setting -> { setting.setInboxSnapshotId(null); settings.save(setting); });
    }
    public String inboxSnapshotId() {
        Long owner = authorization.requireOwner();
        return settings.findById(owner).map(com.coffer.entity.ModelRuntimeSetting::getInboxSnapshotId).orElse(null);
    }
}
