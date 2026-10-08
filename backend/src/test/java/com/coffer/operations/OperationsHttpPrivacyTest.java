package com.coffer.operations;

import com.coffer.auth.domain.*;
import com.coffer.auth.infrastructure.AppUserRepository;
import com.coffer.auth.service.*;
import com.coffer.config.SecurityConfig;
import com.coffer.service.SecretCryptoService;
import com.coffer.exception.GlobalExceptionHandler;
import com.coffer.controller.HealthController;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = OperationsHttpPrivacyTest.Config.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:r26_http;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.locations=classpath:db/migration/h2",
        "coffer.auth.cookie-secure=false", "COFFER_SECRET_KEY=r26-test-key",
        "management.endpoint.health.validate-group-membership=true"})
@AutoConfigureMockMvc @ActiveProfiles({"prod","test"})
class OperationsHttpPrivacyTest {
    @Configuration(proxyBeanMethods = false) @EnableAutoConfiguration @EntityScan("com.coffer")
    @Import({com.coffer.config.HibernateTenantConfig.class, CurrentTenantResolver.class,
            SecurityConfig.class, AppUserDetailsService.class, AdminAuthorization.class, RuntimeController.class,
            MasterKeyRotationController.class, MasterKeyRotationService.class, SecretCryptoService.class,
            RuntimeReadinessIndicator.class, HealthController.class, GlobalExceptionHandler.class,
            com.coffer.exception.UnifiedResultAdvice.class, FailureEndpoint.class})
    static class Config {
        @Bean RuntimeMonitor monitor() { return mock(RuntimeMonitor.class); }
    }
    @RestController static class FailureEndpoint {
        @GetMapping("/api/fixture/failure") public void failure() { throw new IllegalArgumentException(MARKER); }
        @GetMapping("/api/fixture/runtime") public void runtime() { throw new IllegalStateException(MARKER); }
        @GetMapping("/api/fixture/legacy") public com.coffer.dto.Result<Void> legacy() {
            return com.coffer.dto.Result.error(409, "操作状态已变化，请重新核对");
        }
    }
    static final String MARKER = "R26_PRIVATE_FILE_CONTENT_API_KEY_ENDPOINT";
    @Autowired RuntimeMonitor monitor;
    @Autowired AppUserRepository users;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.session.SessionRepository sessions;
    @Autowired MasterKeyRotationService rotation;
    AppUser user, admin;
    @BeforeEach void setup() {
        reset(monitor); TenantContext.clear(); SecurityContextHolder.clearContext();
        user = users.save(new AppUser(UUID.randomUUID().toString(), "x", AuthRole.USER));
        admin = users.save(new AppUser(UUID.randomUUID().toString(), "x", AuthRole.ADMIN));
        snapshot("UP"); when(monitor.history()).thenReturn(List.of());
    }
    void snapshot(String state) {
        var snapshot = new RuntimeMonitor.Snapshot(Instant.now(), state,
                List.of(new RuntimeMonitor.Component("database", state, state.equals("UP") ? "OK" : "DATABASE_UNAVAILABLE", state.equals("UP") ? "NONE" : "CHECK_DATABASE", null, null)), Map.of("pendingTasks", 2L), Map.of(), List.of());
        when(monitor.snapshot()).thenReturn(snapshot);
        when(monitor.publicStatus()).thenReturn(new RuntimeMonitor.Snapshot(snapshot.checkedAt(), state, snapshot.components(), Map.of(), Map.of(), List.of()));
    }
    jakarta.servlet.http.Cookie cookie(AppUser user) {
        var context = SecurityContextHolder.createEmptyContext(); var principal = AuthPrincipal.from(user).forSession();
        context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));
        var session = sessions.createSession(); session.setAttribute("SPRING_SECURITY_CONTEXT",context); sessions.save(session);
        return new jakarta.servlet.http.Cookie("COFFER_SESSION", Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }
    @Test void livenessRemainsUpAndReadinessAndPagesReflectDependencyFailure() throws Exception {
        snapshot("DOWN");
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        String health = mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value("DOWN")).andReturn().getResponse().getContentAsString();
        assertThat(health).doesNotContain("components", "database", MARKER);
        mvc.perform(get("/health")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/admin/runtime").cookie(cookie(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.data.readiness").value("DOWN"));
        mvc.perform(get("/api/runtime/status").cookie(cookie(user))).andExpect(status().isOk()).andExpect(jsonPath("$.data.readiness").value("DOWN"));
    }
    @Test void runtimeAndRotationAreRestrictedToTheirRolesAndRequireCsrf() throws Exception {
        mvc.perform(get("/api/admin/runtime")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/runtime").cookie(cookie(user))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/runtime/key-rotation").cookie(cookie(user))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/runtime/key-rotation").cookie(cookie(admin))).andExpect(status().isForbidden());
        mvc.perform(get("/api/runtime/status").cookie(cookie(admin))).andExpect(status().isForbidden());
        String response = mvc.perform(get("/api/admin/runtime/key-rotation").cookie(cookie(admin))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(MARKER, "r26-test-key", "encrypted", "owner", "provider");
        assertThatThrownBy(rotation::rotate).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void allErrorAndDebugRoutesOmitSensitiveText() throws Exception {
        mvc.perform(get("/api/fixture/legacy").cookie(cookie(user))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
        for (String route : List.of("/api/fixture/failure", "/api/fixture/runtime")) {
            String response = mvc.perform(get(route).cookie(cookie(user))).andExpect(status().is(route.endsWith("runtime") ? 500 : 400)).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(MARKER, "Exception", "trace");
        }
        for (String route : List.of("/h2-console/", "/api/test/hello", "/v3/api-docs", "/swagger-ui/index.html", "/doc.html", "/actuator/env", "/actuator/loggers", "/actuator/metrics"))
            mvc.perform(get(route).cookie(cookie(admin))).andExpect(status().isForbidden());
        when(monitor.history()).thenThrow(new RuntimeStatusUnavailableException());
        mvc.perform(get("/api/admin/runtime/alerts").cookie(cookie(admin))).andExpect(status().isServiceUnavailable());
    }
}
