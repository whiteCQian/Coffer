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
        "spring.session.jdbc.initialize-schema=never", "coffer.privacy.export.max-files=1"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class PrivacyIntegrationTest {
    @Configuration(proxyBeanMethods = false) @EnableAutoConfiguration @EnableAspectJAutoProxy @EntityScan("com.coffer")
    @Import({HibernateTenantConfig.class, CurrentTenantResolver.class, OwnerAuthorization.class, OwnerAuthorizationAspect.class,
            PrivateRequestGateAspect.class, SecurityConfig.class, AppUserDetailsService.class,
            PrivacyController.class, PrivacyService.class, MemoryDeletionWorker.class, RedisMemoryCleanup.class, ChatSessionService.class,
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
        when(storage.readIfUnchanged(anyString(), any())).thenAnswer(i ->
                new ByteArrayInputStream(("BODY:" + i.getArgument(0)).getBytes(StandardCharsets.UTF_8)));
        when(storage.stat(anyString())).thenAnswer(i -> {
            String path = i.getArgument(0);
            byte[] body = ("BODY:" + path).getBytes(StandardCharsets.UTF_8);
            return new com.coffer.file.storage.FileStoragePort.StoredObject(path, body.length, digest(body), "etag");
        });
    }
    String seed(AppUser owner,String marker) {
        return TenantContext.supplyAs(owner.getId(), () -> {
            String path = "users/"+owner.getId()+"/files/"+marker;
            byte[] body = ("BODY:" + path).getBytes(StandardCharsets.UTF_8);
            files.saveAndFlush(FileMetadata.builder().fileName(marker).fileType("txt").fileSize((long)body.length)
                    .storagePath(path).contentSha256(digest(body)).status(FileStatus.COMPLETED).build());
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
    @Test void exportRejectsChangedObjectAndDoesNotOpenItsBody() throws Exception {
        String path = "users/" + a.getId() + "/files/A_PRIVATE";
        when(storage.stat(path)).thenReturn(new com.coffer.file.storage.FileStoragePort.StoredObject(
                path, ("BODY:" + path).getBytes(StandardCharsets.UTF_8).length, "a".repeat(64), "etag"));
        mvc.perform(get("/api/privacy/export").cookie(cookie(a))).andExpect(status().isConflict());
        verify(storage, never()).readIfUnchanged(eq(path), any());
    }
    @Test void exportRejectsBytesChangedAfterTheInitialStat() throws Exception {
        String path = "users/" + a.getId() + "/files/A_PRIVATE";
        when(storage.readIfUnchanged(eq(path), any())).thenReturn(new ByteArrayInputStream(
                "X".repeat(("BODY:" + path).getBytes(StandardCharsets.UTF_8).length)
                        .getBytes(StandardCharsets.UTF_8)));

        mvc.perform(get("/api/privacy/export").cookie(cookie(a))).andExpect(status().isConflict());
        verify(storage).readIfUnchanged(eq(path), any());
    }
    @Test void exportRefusesMoreThanTheConfiguredFileLimit() throws Exception {
        TenantContext.runAs(a.getId(), () -> {
            String path = "users/" + a.getId() + "/files/second";
            byte[] body = ("BODY:" + path).getBytes(StandardCharsets.UTF_8);
            files.saveAndFlush(FileMetadata.builder().fileName("second").fileType("txt")
                    .fileSize((long)body.length).storagePath(path).contentSha256(digest(body))
                    .status(FileStatus.COMPLETED).build());
        });
        mvc.perform(get("/api/privacy/export").cookie(cookie(a))).andExpect(status().isPayloadTooLarge());
        verify(storage, never()).readIfUnchanged(anyString(), any());
    }
    @Test void exportRefusesManifestPastItsByteLimit() throws Exception {
        Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(privacy);
        org.springframework.test.util.ReflectionTestUtils.setField(target, "maxExportBytes", 1L);
        try {
            mvc.perform(get("/api/privacy/export").cookie(cookie(a))).andExpect(status().isPayloadTooLarge());
            verify(storage, never()).readIfUnchanged(anyString(), any());
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(target, "maxExportBytes", 536870912L);
        }
    }
    @Test void exportRefusesTooManyMetadataRows() throws Exception {
        Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(privacy);
        org.springframework.test.util.ReflectionTestUtils.setField(target, "maxExportRecords", 1);
        try {
            mvc.perform(get("/api/privacy/export").cookie(cookie(a))).andExpect(status().isPayloadTooLarge());
            verify(storage, never()).readIfUnchanged(anyString(), any());
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(target, "maxExportRecords", 50000);
        }
    }
    private static String digest(byte[] value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
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
