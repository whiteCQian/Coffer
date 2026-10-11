package com.coffer.desktop;

import com.coffer.CofferApplication;
import com.coffer.auth.domain.*;
import com.coffer.auth.infrastructure.AppUserRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class DesktopPersonalWorkspaceIntegrationTest {
    @TempDir Path temp;
    private static final String TOKEN="a".repeat(64);
    private ConfigurableApplicationContext start(boolean initialize, boolean personal) {
        var context=new SpringApplicationBuilder(CofferApplication.class).run("--spring.profiles.active=desktop", "--coffer.desktop.data-directory="+temp.resolve("data"),
                "--coffer.desktop.initialize="+initialize,"--coffer.desktop.personal="+personal,"--COFFER_DESKTOP_LAUNCH_TOKEN="+TOKEN,
                "--server.port=0","--coffer.embedding.enabled=false","--spring.data.redis.port=1","--coffer.import.inbox.enabled=false");
        context.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().forEach(org.springframework.scheduling.config.ScheduledTask::cancel);
        return context;
    }
    @AfterEach void clear() {
        com.coffer.auth.service.TenantContext.clear();org.springframework.security.core.context.SecurityContextHolder.clearContext();
        var file=((ch.qos.logback.classic.LoggerContext)org.slf4j.LoggerFactory.getILoggerFactory()).getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("FILE");
        if(file instanceof ch.qos.logback.core.FileAppender<?> output && Path.of(output.getFile()).startsWith(temp))output.stop();
    }
    private HttpResponse<String> get(ConfigurableApplicationContext ctx,String path,boolean token) throws Exception {
        int port=((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)ctx).getWebServer().getPort();
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));if(token)request.header("X-Coffer-Desktop-Token",TOKEN);
        return HttpClient.newHttpClient().send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private int post(ConfigurableApplicationContext ctx,String path,String body) throws Exception {
        var csrf=get(ctx,"/api/auth/csrf",true);
        String cookie=csrf.headers().allValues("set-cookie").stream().map(value->value.split(";",2)[0]).filter(value->value.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        int port=((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)ctx).getWebServer().getPort();
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("X-Coffer-Desktop-Token",TOKEN).header("Cookie",cookie).header("X-XSRF-TOKEN",cookie.substring("XSRF-TOKEN=".length())).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    @Test void freshDesktopOpensWithoutLoginKeepsOneIdentityAndBlocksAccountManagement() throws Exception {
        long id;
        try(var ctx=start(true,true)) {
            var json=ctx.getBean(ObjectMapper.class);var me=get(ctx,"/api/auth/me",true);
            assertThat(me.statusCode()).isEqualTo(200);var user=json.readTree(me.body()).path("data");
            id=user.path("id").asLong();assertThat(user.path("username").asText()).isEqualTo("个人文件库");
            assertThat(user.path("role").asText()).isEqualTo("USER");
            assertThat(json.readTree(get(ctx,"/api/auth/status",true).body()).path("data").path("desktopPersonal").asBoolean()).isTrue();
            assertThat(get(ctx,"/api/auth/me",false).statusCode()).isEqualTo(403);
            assertThat(get(ctx,"/api/admin/users",true).statusCode()).isEqualTo(403);
            for(String path:java.util.List.of("/api/auth/login","/api/auth/setup","/api/auth/password","/api/auth/logout","/api/admin/users"))
                assertThat(post(ctx,path,"{\"username\":\"unused\",\"password\":\"Unused-password\"}")).as(path).isEqualTo(403);
            assertThat(get(ctx,"/api/files",true).statusCode()).isEqualTo(200);
            assertThat(ctx.getBean(AppUserRepository.class).count()).isEqualTo(1);
        }
        try(var ctx=start(false,true)) {
            assertThat(ctx.getBean(DesktopPersonalWorkspace.class).principal().id()).isEqualTo(id);
            assertThat(ctx.getBean(AppUserRepository.class).count()).isEqualTo(1);
        }
    }
    @Test void reusesSingleLegacyOwnerAndRefusesAmbiguousMultiOwnerLibrary() {
        long original;
        try(var ctx=start(true,false)) {original=ctx.getBean(AppUserRepository.class).saveAndFlush(new AppUser("old-owner","disabled",AuthRole.USER)).getId();}
        try(var ctx=start(false,true)) {
            assertThat(ctx.getBean(DesktopPersonalWorkspace.class).principal().id()).isEqualTo(original);
            ctx.getBean(AppUserRepository.class).saveAndFlush(new AppUser("second-old-owner","disabled",AuthRole.USER));
        }
        assertThatThrownBy(()->start(false,true)).hasStackTraceContaining("多个业务账号");
        try(var ctx=start(false,false)) {assertThat(ctx.getBean(AppUserRepository.class).count()).isEqualTo(2);}
    }
}
