package com.coffer.auth;

import com.coffer.auth.domain.*;
import com.coffer.auth.infrastructure.*;
import com.coffer.auth.service.*;
import com.coffer.config.*;
import com.coffer.privacy.*;
import com.coffer.repository.*;
import com.coffer.service.*;
import com.coffer.entity.*;
import com.coffer.file.domain.*;
import com.coffer.file.infrastructure.persistence.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipInputStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = PrivacyIntegrationTest.Config.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:r15;DB_CLOSE_DELAY=-1", "spring.jpa.open-in-view=false", "spring.jpa.show-sql=false",
        "spring.session.jdbc.initialize-schema=never"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class PrivacyIntegrationTest {
    @Configuration(proxyBeanMethods = false) @EnableAutoConfiguration @EnableAspectJAutoProxy @EntityScan("com.coffer")
    @Import({HibernateTenantConfig.class, CurrentTenantResolver.class, OwnerAuthorization.class, OwnerAuthorizationAspect.class,
            PrivateRequestGateAspect.class, SecurityConfig.class, AppUserDetailsService.class,
            PrivacyController.class, PrivacyService.class, MemoryDeletionWorker.class, ChatSessionService.class,
            com.coffer.auth.api.AuthExceptionAdvice.class, com.coffer.exception.GlobalExceptionHandler.class})
    static class Config {
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
        @Bean MinioStorageService storage() { return mock(MinioStorageService.class); }
    }
    @Autowired AppUserRepository users;
    @Autowired FileMetadataRepository files;
    @Autowired ChatSessionService sessions;
    @Autowired ChatMessageRepository messages;
    @Autowired PrivacyService privacy;
    @Autowired MemoryDeletionRepository pending;
    @Autowired MemoryDeletionWorker worker;
    @Autowired StringRedisTemplate redis;
    @Autowired MinioStorageService storage;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired org.springframework.session.SessionRepository httpSessions;
    AppUser a, b, admin;
    String sa, sb;
    @BeforeEach void setup() {
        storage = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(storage);
        TenantContext.clear(); SecurityContextHolder.clearContext(); reset(redis, storage);
        String suffix = UUID.randomUUID().toString();
        a=users.save(new AppUser("a-"+suffix,"x",AuthRole.USER)); b=users.save(new AppUser("b-"+suffix,"x",AuthRole.USER));
        admin=users.save(new AppUser("admin-"+suffix,"x",AuthRole.ADMIN));
        sa=seed(a,"A_PRIVATE"); sb=seed(b,"B_SECRET");
        when(storage.getFileStream(isNull(), anyString())).thenAnswer(i -> new ByteArrayInputStream(("BODY:"+i.getArgument(1)).getBytes(StandardCharsets.UTF_8)));
    }
    String seed(AppUser owner,String marker) {
        return TenantContext.supplyAs(owner.getId(), () -> {
            files.saveAndFlush(FileMetadata.builder().fileName(marker).fileType("txt").fileSize(12L).storagePath("users/"+owner.getId()+"/files/"+marker).status(FileStatus.COMPLETED).build());
            String session=sessions.create();
            messages.saveAndFlush(ChatMessage.builder().sessionId(session).userMessage(marker).aiResponse("reply").timestamp(java.time.LocalDateTime.now()).build());
            return session;
        });
    }
    @AfterEach void clear() { TenantContext.clear(); SecurityContextHolder.clearContext(); }
    jakarta.servlet.http.Cookie cookie(AppUser user) {
        var context=SecurityContextHolder.createEmptyContext(); var principal=AuthPrincipal.from(user).forSession();
        context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));
        var session=httpSessions.createSession(); session.setAttribute("SPRING_SECURITY_CONTEXT",context); httpSessions.save(session);
        return new jakarta.servlet.http.Cookie("COFFER_SESSION",Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }
    @Test void exportContainsOnlyOwnersOriginalsAndMetadata() throws Exception {
        var response=mvc.perform(get("/api/privacy/export").cookie(cookie(a))).andExpect(status().isOk()).andReturn().getResponse();
        Map<String,String> entries=new HashMap<>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(response.getContentAsByteArray()),StandardCharsets.UTF_8)) {
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) entries.put(entry.getName(),new String(zip.readAllBytes(),StandardCharsets.UTF_8));
        }
        assertThat(entries).containsKey("EXPORT_COMPLETE");
        assertThat(entries.get("manifest.json")).contains("A_PRIVATE").doesNotContain("B_SECRET","password_hash","encrypted_configuration");
        assertThat(entries.values().toString()).contains("BODY:users/"+a.getId()).doesNotContain("BODY:users/"+b.getId());
        mvc.perform(get("/api/privacy/export").cookie(cookie(admin))).andExpect(status().isForbidden());
        mvc.perform(get("/api/privacy/export")).andExpect(status().isUnauthorized());
    }
    @Test void deletionImmediatelyInvalidatesSessionsAndRetriesOnlyTheirExactMemoryKeys() throws Exception {
        var csrf=new jakarta.servlet.http.Cookie("XSRF-TOKEN","token");
        mvc.perform(delete("/api/privacy/conversations").cookie(cookie(a),csrf).header("X-XSRF-TOKEN","token")
                .contentType("application/json").content("{\"confirmation\":\"DELETE_MY_CONVERSATIONS\"}")).andExpect(status().isOk());
        TenantContext.runAs(a.getId(), () -> {
            assertThat(messages.count()).isZero(); assertThat(pending.count()).isEqualTo(1);
            assertThatThrownBy(() -> sessions.require(sa)).isInstanceOf(ResourceNotFoundException.class);
            when(redis.delete(anyString())).thenThrow(new IllegalStateException("offline"));
            worker.process(); assertThat(pending.count()).isEqualTo(1);
            doReturn(true).when(redis).delete(anyString());
            worker.process(); assertThat(pending.count()).isZero();
            assertThat(files.count()).isEqualTo(1);
        });
        verify(redis,times(2)).delete("chat:memory:v2:"+a.getId()+":"+sa);
        TenantContext.runAs(b.getId(), () -> { assertThat(messages.count()).isEqualTo(1); assertThat(sessions.require(sb)).isEqualTo(b.getId()); });
    }
    @Test void eraseRefusesConcurrentWorkAndWrongConfirmation() throws Exception {
        var csrf=new jakarta.servlet.http.Cookie("XSRF-TOKEN","token");
        try(var lease=PrivateWorkspaceGate.enter(a.getId())) {
            mvc.perform(delete("/api/privacy/conversations").cookie(cookie(a),csrf).header("X-XSRF-TOKEN","token")
                    .contentType("application/json").content("{\"confirmation\":\"DELETE_MY_CONVERSATIONS\"}")).andExpect(status().isConflict());
        }
        mvc.perform(delete("/api/privacy/conversations").cookie(cookie(a),csrf).header("X-XSRF-TOKEN","token")
                .contentType("application/json").content("{\"confirmation\":\"no\"}")).andExpect(status().isBadRequest());
        TenantContext.runAs(a.getId(), () -> assertThat(messages.count()).isEqualTo(1));
    }
}
