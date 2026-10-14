package com.coffer.model.runtime;

import com.coffer.auth.service.AdminAuthorization;
import com.coffer.auth.service.OwnerOnly;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.service.SecretCryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.UUID;

@Service @RequiredArgsConstructor
public class AdminEmbeddingService {
    private final AdminEmbeddingSettingRepository repository;
    private final SecretCryptoService crypto;
    private final AdminAuthorization admin;
    private final ModelRuntimeConnectivityTester tester;
    @Value("${coffer.models.allowed-api-hosts:api.deepseek.com,dashscope.aliyuncs.com,maas.qianwenaiapi.com}") private String allowedApiHosts;
    @Value("${coffer.runtime.local.allowed-hosts:localhost,127.0.0.1,::1}") private String allowedLocalHosts;
    @Value("${coffer.runtime.local.allowed-ports:11434,1234,8000}") private String allowedLocalPorts;

    public record AdminView(GovernanceRunMode mode, String baseUrl, String modelName, Integer dimensions,
                            boolean configured, String maskedApiKey, boolean enabled, LocalDateTime validatedAt,
                            String configurationVersion) { }
    public record UserView(boolean configured, boolean enabled, boolean available, GovernanceRunMode mode,
                           String baseUrl, String modelName, Integer dimensions, String message) { }
    public record TestResult(boolean success, String message, AdminView configuration) { }

    private AdminEmbeddingSetting setting() { return repository.findById(1L).orElseGet(AdminEmbeddingSetting::new); }
    private boolean configured(AdminEmbeddingSetting s) {
        return !s.getBaseUrl().isBlank() && !s.getModelName().isBlank()
                && (s.getMode() == GovernanceRunMode.LOCAL || s.getEncryptedApiKey() != null && !s.getEncryptedApiKey().isBlank());
    }
    private String key(AdminEmbeddingSetting s) {
        return s.getEncryptedApiKey() == null || s.getEncryptedApiKey().isBlank() ? "" : crypto.decrypt(s.getEncryptedApiKey());
    }
    private AdminView view(AdminEmbeddingSetting s) {
        String k = key(s);
        return new AdminView(s.getMode(), s.getBaseUrl(), s.getModelName(), s.getDimensions(), configured(s),
                k.isBlank() ? "" : k.length() <= 4 ? "********" : "********" + k.substring(k.length() - 4),
                s.isEnabled(), s.getValidatedAt(), s.getConfigurationVersion());
    }
    @Transactional(readOnly = true)
    public AdminView getAdmin() { admin.requireAdmin(); return view(setting()); }

    @Transactional
    public AdminView save(AdminEmbeddingRequest request) {
        admin.requireAdmin();
        var s = setting();
        GovernanceRunMode mode = request.getMode() == null ? GovernanceRunMode.API : request.getMode();
        String url = ModelRuntimeEndpointConfigurationService.validateAddress(mode, request.getBaseUrl(),
                allowedApiHosts, allowedLocalHosts, allowedLocalPorts);
        if (request.getModelName() == null || request.getModelName().isBlank() || request.getModelName().length() > 128)
            throw new IllegalArgumentException("请填写向量模型名称");
        if (request.getDimensions() == null || request.getDimensions() < 1 || request.getDimensions() > 8192)
            throw new IllegalArgumentException("向量维度应在 1 到 8192 之间");
        s.setMode(mode); s.setBaseUrl(url); s.setModelName(request.getModelName().trim()); s.setDimensions(request.getDimensions());
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) s.setEncryptedApiKey(crypto.encrypt(request.getApiKey().trim()));
        s.setEnabled(false); s.setValidatedAt(null); s.setConfigurationVersion(UUID.randomUUID().toString());
        s.setUpdatedAt(LocalDateTime.now());
        return view(repository.saveAndFlush(s));
    }
    @Transactional
    public TestResult validate() {
        admin.requireAdmin();
        var s = setting();
        if (!configured(s)) return new TestResult(false, "请先保存向量模型地址、名称和对应 Key", view(s));
        ModelRuntimeEndpointConfigurationService.validateAddress(s.getMode(), s.getBaseUrl(), allowedApiHosts, allowedLocalHosts, allowedLocalPorts);
        var result = tester.test(endpoint(s, true), s.getDimensions());
        s.setValidatedAt(result.isSuccess() ? LocalDateTime.now() : null);
        if (!result.isSuccess()) s.setEnabled(false);
        s.setUpdatedAt(LocalDateTime.now()); repository.saveAndFlush(s);
        return new TestResult(result.isSuccess(), result.getMessage(), view(s));
    }
    @Transactional
    public AdminView enable(boolean enabled) {
        admin.requireAdmin(); var s = setting();
        if (enabled && (!configured(s) || s.getValidatedAt() == null))
            throw new IllegalArgumentException("请先保存配置并通过向量连接测试");
        s.setEnabled(enabled); s.setConfigurationVersion(UUID.randomUUID().toString()); s.setUpdatedAt(LocalDateTime.now());
        return view(repository.saveAndFlush(s));
    }
    @Transactional
    public AdminView clear() {
        admin.requireAdmin(); var s = setting();
        s.setEnabled(false); s.setValidatedAt(null); s.setEncryptedApiKey(null); s.setBaseUrl(""); s.setModelName("");
        s.setConfigurationVersion(UUID.randomUUID().toString()); s.setUpdatedAt(LocalDateTime.now());
        return view(repository.saveAndFlush(s));
    }
    @OwnerOnly @Transactional(readOnly = true)
    public UserView status(GovernanceRunMode userMode) {
        var s = setting(); boolean available = available(s, userMode);
        String message = !configured(s) ? "管理员尚未配置语义向量检索，当前使用关键词 / 全文检索"
                : !s.isEnabled() || s.getValidatedAt() == null ? "管理员尚未启用语义向量检索，当前使用关键词 / 全文检索"
                : s.getMode() != userMode ? "当前模型模式与管理员向量服务模式不同，使用关键词 / 全文检索"
                : "语义向量检索由管理员统一提供；更换模型后需重新确认目标并重建个人索引";
        return new UserView(configured(s), s.isEnabled(), available, s.getMode(), s.getBaseUrl(), s.getModelName(), s.getDimensions(), message);
    }
    private boolean available(AdminEmbeddingSetting s, GovernanceRunMode mode) {
        return configured(s) && s.isEnabled() && s.getValidatedAt() != null && s.getMode() == mode;
    }
    @OwnerOnly @Transactional(readOnly = true)
    public boolean isAvailable(GovernanceRunMode mode) { return available(setting(), mode); }
    @OwnerOnly @Transactional(readOnly = true)
    public ResolvedModelRuntimeEndpoint metadata(GovernanceRunMode mode) {
        var s = setting(); if (!available(s, mode)) throw new AccessDeniedException("语义向量检索尚未由管理员启用");
        return endpoint(s, false);
    }
    private ResolvedModelRuntimeEndpoint endpoint(AdminEmbeddingSetting s, boolean withKey) {
        return new ResolvedModelRuntimeEndpoint(s.getMode(), ModelRuntimeCapability.EMBEDDING, s.getBaseUrl(), s.getModelName(),
                withKey ? key(s) : "", "ADMIN:" + s.getConfigurationVersion(), true, null);
    }
    /** Admin Key is loaded only here, after checking the owner's immutable approved target. */
    public record Execution(ResolvedModelRuntimeEndpoint endpoint, Integer dimensions) { }
    @OwnerOnly @Transactional(readOnly = true)
    public Execution execution(ResolvedModelRuntimeEndpoint approved, GovernanceRunMode mode) {
        var s = setting();
        if (!available(s, mode) || approved == null || !endpoint(s, false).equals(approved)) throw new ModelConsentRequiredException();
        ModelRuntimeEndpointConfigurationService.validateAddress(s.getMode(), s.getBaseUrl(), allowedApiHosts, allowedLocalHosts, allowedLocalPorts);
        return new Execution(endpoint(s, true), s.getDimensions());
    }
    @OwnerOnly @Transactional(readOnly = true)
    public Integer dimensions() { return setting().getDimensions(); }
    @OwnerOnly @Transactional(readOnly = true)
    public String indexVersion() {
        var s = setting();
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest((s.getMode() + "\n" + s.getBaseUrl() + "\n" + s.getModelName() + "\n" + s.getDimensions()).getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("无法确定向量索引版本"); }
    }
}
