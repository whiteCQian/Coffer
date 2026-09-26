package com.coffer.auth;

import com.coffer.auth.domain.*;
import com.coffer.auth.infrastructure.*;
import com.coffer.auth.service.*;
import com.coffer.config.*;
import com.coffer.entity.*;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.model.runtime.*;
import com.coffer.model.runtime.api.*;
import com.coffer.model.provider.*;
import com.coffer.repository.*;
import com.coffer.service.*;
import com.coffer.task.application.TaskRegistrationService;
import com.coffer.task.infrastructure.persistence.AsyncTaskRepository;
import com.coffer.dto.Result;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = ModelPrivacyIntegrationTest.Config.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:r14;DB_CLOSE_DELAY=-1", "spring.jpa.open-in-view=false", "spring.jpa.show-sql=false",
        "spring.session.jdbc.initialize-schema=never", "COFFER_SECRET_KEY=r14-test-master"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ModelPrivacyIntegrationTest {
    static final Endpoint first = new Endpoint();
    static final Endpoint second = new Endpoint();
    static class Endpoint {
        final HttpServer server;
        final List<String> requests = new CopyOnWriteArrayList<>();
        volatile boolean fail;
        volatile String redirect;
        Endpoint() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/v1/chat/completions", exchange -> {
                    requests.add(exchange.getRequestHeaders().getFirst("Authorization") + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    if (redirect != null) {
                        exchange.getResponseHeaders().add("Location", redirect);
                        exchange.sendResponseHeaders(307, -1); exchange.close(); return;
                    }
                    String body = fail ? "{\"error\":{\"message\":\"R14_BODY_MARKER R14_KEY_MARKER\"}}" :
                            "{\"id\":\"test\",\"object\":\"chat.completion\",\"model\":\"R14_BODY_MARKER\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(fail ? 500 : 200, bytes.length);
                    exchange.getResponseBody().write(bytes); exchange.close();
                });
                server.start();
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
    }
    @DynamicPropertySource static void ports(DynamicPropertyRegistry properties) {
        properties.add("coffer.runtime.local.allowed-ports", () -> first.server.getAddress().getPort() + "," + second.server.getAddress().getPort());
    }
    @AfterAll static void stop() { first.server.stop(0); second.server.stop(0); }
    @Configuration(proxyBeanMethods = false) @EnableAutoConfiguration @EnableAspectJAutoProxy @EntityScan("com.coffer")
    @Import({HibernateTenantConfig.class, CurrentTenantResolver.class, OwnerAuthorization.class, OwnerAuthorizationAspect.class,
            SecurityConfig.class, AppUserDetailsService.class, ModelRuntimeModeService.class,
            ModelRuntimeEndpointConfigurationService.class, ModelRuntimeProviderFactory.class, SecretCryptoService.class,
            ModelCredentialService.class, ModelExecutionSnapshotService.class, ModelSubmissionAspect.class,
            ModelExecutionSnapshotController.class, UserSecretRotationService.class, TaskRegistrationService.class,
            ModelDiagnosticsService.class, com.coffer.controller.ModelDiagnosticsController.class,
            com.coffer.task.DiagnosticRetentionTask.class,
            com.coffer.exception.GlobalExceptionHandler.class, Probe.class})
    static class Config {
        @Bean ModelRuntimeProperties properties() { return new ModelRuntimeProperties(); }
        @Bean EmbeddingProperties embeddingProperties() { return new EmbeddingProperties(); }
        @Bean ChatProvider model(ModelRuntimeModeService modes, ModelRuntimeProviderFactory factory) { return new ModeAwareChatProvider(modes, factory); }
    }
    @RestController static class Probe {
        private final ChatProvider model;
        Probe(ChatProvider model) { this.model = model; }
        @PostMapping("/api/model-probe") @ModelSubmission("TEST")
        public Result<String> send() { return Result.success(model.chat("R14_BODY_MARKER")); }
    }
    @Autowired AppUserRepository users;
    @Autowired ModelRuntimeSettingRepository settings;
    @Autowired ModelRuntimeEndpointConfigurationService configuration;
    @Autowired ModelExecutionSnapshotService snapshots;
    @Autowired ModelExecutionSnapshotRepository snapshotRows;
    @Autowired ModelCredentialService credentials;
    @Autowired ModelCredentialRepository credentialRows;
    @Autowired ChatProvider model;
    @Autowired TaskRegistrationService tasks;
    @Autowired AsyncTaskRepository taskRows;
    @Autowired org.springframework.session.SessionRepository httpSessions;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired ModelCallLogRepository logs;
    @Autowired ModelDiagnosticsService diagnostics;
    @Autowired com.coffer.task.DiagnosticRetentionTask retention;
    AppUser a, b, admin;
    @BeforeEach void setup() {
        TenantContext.clear(); SecurityContextHolder.clearContext();
        first.requests.clear(); second.requests.clear(); first.fail=false; second.fail=false;
        first.redirect=null; second.redirect=null;
        String suffix = UUID.randomUUID().toString();
        a = users.save(new AppUser("a-" + suffix, "x", AuthRole.USER));
        b = users.save(new AppUser("b-" + suffix, "x", AuthRole.USER));
        admin = users.save(new AppUser("admin-" + suffix, "x", AuthRole.ADMIN));
        TenantContext.runAs(a.getId(), () -> configure(first, "R14_KEY_MARKER"));
        TenantContext.runAs(b.getId(), () -> configure(second, "B_KEY"));
    }
    @AfterEach void clear() { TenantContext.clear(); SecurityContextHolder.clearContext(); }
    void configure(Endpoint endpoint, String key) {
        for (var capability : ModelRuntimeCapability.values()) {
            var request = new ModelRuntimeEndpointRequest();
            request.setMode(GovernanceRunMode.LOCAL); request.setCapability(capability);
            request.setBaseUrl(endpoint.url()); request.setModelName("test-model"); request.setApiKey(key);
            configuration.save(request);
        }
        Long owner = TenantContext.requireOwnerId();
        var setting = settings.findById(owner).orElseThrow();
        setting.setActiveMode(GovernanceRunMode.LOCAL); setting.setLocalValidatedAt(LocalDateTime.now()); settings.saveAndFlush(setting);
    }
    @Test void existingTaskKeepsDestinationAndCredentialAfterConfigurationChanges() {
        TenantContext.runAs(a.getId(), () -> {
            var snapshot = snapshots.capture(snapshots.preview().configurationVersion(), true, "UPLOAD");
            ModelExecutionContext.with(snapshot, () -> tasks.createPendingTask("pinned-task", "file"));
            String id = taskRows.findByTaskId("pinned-task").orElseThrow().getModelSnapshotId();
            String cipher = snapshotRows.findById(id).orElseThrow().getEncryptedConfiguration();
            assertThat(cipher).doesNotContain("R14_KEY_MARKER", first.url());
            configure(second, "NEW_KEY");
            snapshots.with(id, () -> assertThat(model.chat("R14_BODY_MARKER")).isEqualTo("ok"));
            assertThat(first.requests).hasSize(1);
            assertThat(first.requests.get(0)).contains("R14_KEY_MARKER", "R14_BODY_MARKER");
            assertThat(second.requests).isEmpty();
            var newer = snapshots.capture(snapshots.preview().configurationVersion(), true, "CHAT");
            ModelExecutionContext.with(newer, () -> model.chat("new"));
            assertThat(second.requests).hasSize(1);
            assertThat(second.requests.get(0)).contains("NEW_KEY").doesNotContain("R14_KEY_MARKER");
        });
        assertThat(ModelExecutionContext.current()).isNull();
    }
    @Test void noConsentStaleConsentAndCrossOwnerSnapshotsNeverSendContent() {
        var snapshot = TenantContext.supplyAs(a.getId(), () -> snapshots.capture(snapshots.preview().configurationVersion(), true, "CHAT"));
        TenantContext.runAs(a.getId(), () -> {
            assertThatThrownBy(() -> model.chat("R14_BODY_MARKER")).isInstanceOf(ModelConsentRequiredException.class);
            assertThatThrownBy(() -> snapshots.capture(snapshot.version(), false, "CHAT")).isInstanceOf(ModelConsentRequiredException.class);
            configure(second, "changed");
            assertThatThrownBy(() -> snapshots.capture(snapshot.version(), true, "CHAT")).isInstanceOf(ModelConsentRequiredException.class);
        });
        TenantContext.runAs(b.getId(), () -> {
            assertThatThrownBy(() -> snapshots.load(snapshot.id())).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> ModelExecutionContext.with(snapshot, () -> model.chat("R14_BODY_MARKER"))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        });
        TenantContext.runAs(admin.getId(), () -> assertThatThrownBy(snapshots::preview).isInstanceOf(org.springframework.security.access.AccessDeniedException.class));
        assertThat(first.requests).isEmpty(); assertThat(second.requests).isEmpty();
    }
    @Test void providerFailureCannotExposeBodyOrKeyThroughErrorsOrLogs(CapturedOutput output) {
        first.fail = true;
        TenantContext.runAs(a.getId(), () -> {
            var snapshot = snapshots.capture(snapshots.preview().configurationVersion(), true, "CHAT");
            assertThatThrownBy(() -> ModelExecutionContext.with(snapshot, () -> model.chat("R14_BODY_MARKER")))
                    .isInstanceOf(ModelInvocationException.class).hasMessageNotContaining("R14_BODY_MARKER").hasMessageNotContaining("R14_KEY_MARKER").hasNoCause();
            assertThat(snapshot.toString()).doesNotContain("R14_KEY_MARKER");
            assertThat(snapshots.describe(snapshot.id()).toString()).doesNotContain("R14_KEY_MARKER");
        });
        assertThat(output.getAll()).doesNotContain("R14_BODY_MARKER", "R14_KEY_MARKER");
    }
    @Test void redirectsNeverForwardBodyOrKeyToAnotherEndpoint() {
        first.redirect = second.url() + "/chat/completions";
        TenantContext.runAs(a.getId(), () -> {
            var snapshot = snapshots.capture(snapshots.preview().configurationVersion(), true, "CHAT");
            assertThatThrownBy(() -> ModelExecutionContext.with(snapshot, () -> model.chat("R14_BODY_MARKER")))
                    .isInstanceOf(ModelInvocationException.class);
        });
        assertThat(first.requests).hasSize(1);
        assertThat(second.requests).isEmpty();
    }
    @Test void diagnosticsAreSanitizedOwnerScopedAndExpireForDisabledAccounts() throws Exception {
        TenantContext.runAs(a.getId(), () -> logs.saveAndFlush(ModelCallLog.builder().callTime(LocalDateTime.now())
                .userMessage("R14_BODY_MARKER").modelName("R14_KEY_MARKER").errorMessage("R14_BODY_MARKER").status("FAILED").build()));
        TenantContext.runAs(b.getId(), () -> logs.saveAndFlush(ModelCallLog.builder().callTime(LocalDateTime.now().minusDays(31)).status("SUCCESS").build()));
        TenantContext.runAs(a.getId(), () -> assertThat(diagnostics.list(0).getContent().toString()).doesNotContain("R14_BODY_MARKER", "R14_KEY_MARKER"));
        mvc.perform(get("/api/model-diagnostics").cookie(cookie(admin))).andExpect(status().isForbidden());
        TenantContext.runAs(a.getId(), diagnostics::delete);
        TenantContext.runAs(a.getId(), () -> assertThat(logs.count()).isZero());
        TenantContext.runAs(b.getId(), () -> assertThat(logs.count()).isEqualTo(1));
        b.setEnabled(false); users.saveAndFlush(b);
        retention.purgeExpired();
        b.setEnabled(true); users.saveAndFlush(b);
        TenantContext.runAs(b.getId(), () -> assertThat(logs.count()).isZero());
    }
    @Test void endpointPolicyRejectsInternalProbesCredentialsQueriesAndTraversal() {
        TenantContext.runAs(a.getId(), () -> {
            for (String url : List.of("http://127.0.0.1:22/v1", "http://169.254.169.254/v1", first.url()+"/../admin", first.url()+"?api_key=R14_KEY_MARKER", "http://x:y@127.0.0.1/v1")) {
                var request = new ModelRuntimeEndpointRequest(); request.setMode(GovernanceRunMode.LOCAL); request.setCapability(ModelRuntimeCapability.CHAT); request.setModelName("test"); request.setBaseUrl(url);
                assertThatThrownBy(() -> configuration.save(request)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("R14_KEY_MARKER");
            }
            assertThatThrownBy(() -> configuration.assertApiEndpointAllowed("http://api.deepseek.com/v1")).isInstanceOf(IllegalArgumentException.class);
        });
        assertThat(first.requests).isEmpty();
    }
    @Test void inboxConsentIsExplicitOwnerScopedAndRevocable() {
        TenantContext.runAs(a.getId(), () -> {
            assertThat(snapshots.inboxSnapshotId()).isNull();
            var snapshot = snapshots.capture(snapshots.preview().configurationVersion(), true, "INBOX");
            ModelExecutionContext.with(snapshot, snapshots::authorizeInbox);
            assertThat(snapshots.inboxSnapshotId()).isEqualTo(snapshot.id());
            snapshots.revokeInbox(); assertThat(snapshots.inboxSnapshotId()).isNull();
        });
        TenantContext.runAs(b.getId(), () -> assertThat(snapshots.inboxSnapshotId()).isNull());
    }
    jakarta.servlet.http.Cookie cookie(AppUser user) {
        var context = SecurityContextHolder.createEmptyContext(); var principal = AuthPrincipal.from(user).forSession();
        context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        var session = httpSessions.createSession(); session.setAttribute("SPRING_SECURITY_CONTEXT", context); httpSessions.save(session);
        return new jakarta.servlet.http.Cookie("COFFER_SESSION", Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }
    @Test void httpSubmissionRequiresMatchingConsentAndAdminCannotPreviewTargets() throws Exception {
        var csrf = new jakarta.servlet.http.Cookie("XSRF-TOKEN", "token");
        mvc.perform(post("/api/model-probe").cookie(cookie(a), csrf).header("X-XSRF-TOKEN", "token")).andExpect(status().isPreconditionRequired());
        String version = TenantContext.supplyAs(a.getId(), () -> snapshots.preview().configurationVersion());
        mvc.perform(post("/api/model-probe").cookie(cookie(a), csrf).header("X-XSRF-TOKEN", "token")
                .header("X-Coffer-Model-Version", version).header("X-Coffer-Allow-Sensitive", "true")).andExpect(status().isOk());
        mvc.perform(get("/api/model-execution/target").cookie(cookie(admin))).andExpect(status().isForbidden());
        assertThat(first.requests).hasSize(1);
    }
}
