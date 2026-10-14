package com.coffer.auth;

import com.coffer.auth.domain.*;
import com.coffer.auth.infrastructure.AppUserRepository;
import com.coffer.auth.service.*;
import com.coffer.config.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=AccountDeletionIntegrationTest.Config.class,properties={
        "spring.datasource.url=jdbc:h2:mem:account_deletion;DB_CLOSE_DELAY=-1","spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false","spring.session.jdbc.initialize-schema=never"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class AccountDeletionIntegrationTest {
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableAspectJAutoProxy @EntityScan("com.coffer")
    @Import({HibernateTenantConfig.class,CurrentTenantResolver.class,OwnerAuthorization.class,OwnerAuthorizationAspect.class,
            SecurityConfig.class,AppUserDetailsService.class,AdminAuthorization.class,AccountService.class,
            AccountDeletionService.class,AccountDatabasePurger.class,AccountDeletionWorker.class,
            com.coffer.auth.api.AdminUserController.class,RequestRateLimiter.class,ClientAddressResolver.class,
            com.coffer.exception.GlobalExceptionHandler.class,com.coffer.auth.api.AuthExceptionAdvice.class})
    static class Config {
        @Bean AccountObjectPurger objects() { return mock(AccountObjectPurger.class); }
        @Bean AccountCachePurger caches() { return mock(AccountCachePurger.class); }
    }
    @Autowired AppUserRepository users;
    @Autowired AccountDeletionWorker worker;
    @Autowired AccountObjectPurger objects;
    @Autowired AccountCachePurger caches;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired org.springframework.session.SessionRepository sessions;
    AppUser admin,a,b;
    jakarta.servlet.http.Cookie adminCookie,aCookie;
    @BeforeEach void setup() {
        TenantContext.clear(); SecurityContextHolder.clearContext(); reset(objects,caches);
        String suffix=UUID.randomUUID().toString();
        admin=users.saveAndFlush(new AppUser("delete-admin-"+suffix,"unused",AuthRole.ADMIN));
        a=users.saveAndFlush(new AppUser("delete-a-"+suffix,"unused",AuthRole.USER));
        b=users.saveAndFlush(new AppUser("delete-b-"+suffix,"unused",AuthRole.USER));
        adminCookie=cookie(admin); aCookie=cookie(a);
    }
    @AfterEach void clear() { TenantContext.clear(); SecurityContextHolder.clearContext(); }
    jakarta.servlet.http.Cookie cookie(AppUser user) {
        Session session=(Session)sessions.createSession();
        var context=SecurityContextHolder.createEmptyContext(); var principal=AuthPrincipal.from(user).forSession();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));
        session.setAttribute("SPRING_SECURITY_CONTEXT",context); sessions.save(session);
        return new jakarta.servlet.http.Cookie("COFFER_SESSION",Base64.getEncoder().encodeToString(session.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder deleteRequest(AppUser target,jakarta.servlet.http.Cookie actor,String confirmation) {
        return delete("/api/admin/users/"+target.getId()).cookie(actor,new jakarta.servlet.http.Cookie("XSRF-TOKEN","token"))
                .header("X-XSRF-TOKEN","token").contentType("application/json")
                .content("{\"confirmation\":\""+confirmation+"\",\"deleteData\":true}");
    }
    void request(AppUser target) throws Exception { mvc.perform(deleteRequest(target,adminCookie,target.getUsername())).andExpect(status().isOk()); }
    long count(String table,long owner) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE owner_id=?",Long.class,owner); }
    void content(AppUser user) {
        long owner=user.getId();
        jdbc.update("INSERT INTO file_metadata(owner_id,file_name,file_size,status,archived,storage_path) VALUES(?,'private-fixture.txt',5,'COMPLETED',FALSE,?)",owner,"users/"+owner+"/objects/fixture.txt");
        jdbc.update("INSERT INTO chat_message(owner_id,session_id,user_message) VALUES(?,'fixture','private synthetic conversation')",owner);
        jdbc.update("INSERT INTO user_model_credential(owner_id,provider,encrypted_api_key,updated_at) VALUES(?,'DEEPSEEK','synthetic-cipher',CURRENT_TIMESTAMP)",owner);
        jdbc.update("INSERT INTO async_task(owner_id,task_id,status) VALUES(?,?,'PENDING')",owner,UUID.randomUUID().toString());
        String document=UUID.randomUUID().toString();
        long file=jdbc.queryForObject("SELECT id FROM file_metadata WHERE owner_id=?",Long.class,owner);
        jdbc.update("INSERT INTO parsed_document(id,owner_id,file_id,revision,format,parser_version,status,content_sha256,parsed_at) VALUES(?,?,?,0,'TEXT','fixture','SUCCESS',?,CURRENT_TIMESTAMP)",document,owner,file,"a".repeat(64));
        jdbc.update("INSERT INTO parsed_chunk(owner_id,document_id,ordinal,original_text,source_kind,source_start,source_end,start_character,end_character) VALUES(?,?,0,'private fixture','PARAGRAPH',0,0,0,15)",owner,document);
    }
    @Test void userAndAnonymousCannotDeleteAndConfirmationProtectsState() throws Exception {
        mvc.perform(delete("/api/admin/users/"+a.getId()).cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN","token"))
                .header("X-XSRF-TOKEN","token").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(deleteRequest(b,aCookie,b.getUsername())).andExpect(status().isForbidden());
        mvc.perform(deleteRequest(a,adminCookie,"wrong-account")).andExpect(status().isBadRequest());
        assertThat(users.findById(a.getId()).orElseThrow().isEnabled()).isTrue(); verifyNoInteractions(objects,caches);
    }
    @Test void currentAdminCannotBeDeletedAndOtherDisabledAdminCanBeRemoved() throws Exception {
        mvc.perform(deleteRequest(admin,adminCookie,admin.getUsername())).andExpect(status().isConflict());
        var other=users.saveAndFlush(new AppUser("other-admin-"+UUID.randomUUID(),"unused",AuthRole.ADMIN));
        other.setEnabled(false); users.saveAndFlush(other); request(other); worker.process(other.getId());
        assertThat(users.findById(other.getId())).isEmpty(); assertThat(users.findById(admin.getId()).orElseThrow().isEnabled()).isTrue();
    }
    @Test void deletionRevokesSessionThenPurgesOnlyTheChosenOwnersData() throws Exception {
        content(a); content(b); request(a); request(a);
        mvc.perform(get("/api/auth/me").cookie(aCookie)).andExpect(status().isUnauthorized());
        assertThat(users.findById(a.getId()).orElseThrow().getDeletionStatus()).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_deletion_job WHERE target_user_id=?",Long.class,a.getId())).isEqualTo(1);
        mvc.perform(patch("/api/admin/users/"+a.getId()+"/enable").cookie(adminCookie,new jakarta.servlet.http.Cookie("XSRF-TOKEN","token"))
                .header("X-XSRF-TOKEN","token")).andExpect(status().isConflict());
        worker.process(a.getId());
        assertThat(users.findById(a.getId())).isEmpty(); verify(objects).purge(a.getId()); verify(caches).purge(a.getId());
        for(String table:List.of("file_metadata","chat_message","user_model_credential","async_task","parsed_document","parsed_chunk")) {
            assertThat(count(table,a.getId())).isZero(); assertThat(count(table,b.getId())).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM account_deletion_job WHERE target_user_id=?",String.class,a.getId())).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_embedding_setting",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_audit_log WHERE target_user_id=? AND action='ACCOUNT_DELETED'",Long.class,a.getId())).isEqualTo(1);
    }
    @Test void failedStorageCleanupKeepsDataDisabledAndCanBeRetried() throws Exception {
        content(a); request(a); doThrow(new IllegalStateException("private-error-marker")).when(objects).purge(a.getId());
        worker.process(a.getId()); assertThat(users.findById(a.getId()).orElseThrow().getDeletionStatus()).isEqualTo("FAILED");
        assertThat(count("file_metadata",a.getId())).isEqualTo(1); verifyNoInteractions(caches);
        doNothing().when(objects).purge(a.getId());
        mvc.perform(post("/api/admin/users/"+a.getId()+"/retry-deletion").cookie(adminCookie,new jakarta.servlet.http.Cookie("XSRF-TOKEN","token"))
                .header("X-XSRF-TOKEN","token")).andExpect(status().isOk());
        worker.process(a.getId()); assertThat(users.findById(a.getId())).isEmpty();
    }
    @Test void activeWorkspaceBlocksCleanupUntilTheWorkHasFinished() throws Exception {
        request(a);
        try(var work=PrivateWorkspaceGate.enter(a.getId())) { worker.process(a.getId()); verifyNoInteractions(objects,caches); }
        assertThat(users.findById(a.getId()).orElseThrow().getDeletionStatus()).isEqualTo("QUEUED");
        jdbc.update("UPDATE account_deletion_job SET next_attempt_at=CURRENT_TIMESTAMP WHERE target_user_id=?",a.getId());
        worker.process(a.getId()); assertThat(users.findById(a.getId())).isEmpty();
    }
}
