package com.coffer.desktop;

import com.coffer.CofferApplication;
import com.coffer.auth.domain.*;
import com.coffer.auth.infrastructure.AppUserRepository;
import com.coffer.auth.service.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import static org.assertj.core.api.Assertions.*;

class DesktopNativeBridgeIntegrationTest {
    @TempDir Path temp;
    @AfterEach void clear() {
        TenantContext.clear();
        var file=((ch.qos.logback.classic.LoggerContext)org.slf4j.LoggerFactory.getILoggerFactory()).getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("FILE");
        if(file instanceof ch.qos.logback.core.FileAppender<?> output && Path.of(output.getFile()).startsWith(temp))output.stop();
    }
    private ConfigurableApplicationContext start() {
        var context=new SpringApplicationBuilder(CofferApplication.class).run("--spring.profiles.active=desktop","--coffer.desktop.data-directory="+temp.resolve("data"),
                "--coffer.desktop.initialize=true","--server.port=0","--coffer.import.inbox.enabled=true","--coffer.embedding.enabled=false","--spring.data.redis.port=1");
        context.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().forEach(org.springframework.scheduling.config.ScheduledTask::cancel);
        TenantContext.set(context.getBean(AppUserRepository.class).saveAndFlush(new AppUser("native-user","disabled",AuthRole.USER)).getId());return context;
    }
    @Test void confirmedNativeCopyRetainsSourceAndCollisionsUseDisplayedUniqueInboxPath() throws Exception {
        try(var ctx=start()) {
            var service=ctx.getBean(NativeInboxService.class);Path source=Files.writeString(temp.resolve("中文源.txt"),"source-retained");
            var first=service.preview(source.toString());service.commit(source.toString(),first);
            assertThat(source).hasContent("source-retained");Path root=ctx.getBean(DesktopDataDirectory.class).libraryRoot();
            assertThat(root.resolve(first.targetPath())).hasContent("source-retained");
            assertThatThrownBy(()->service.preview(ctx.getBean(DesktopDataDirectory.class).root().resolve(DesktopDataDirectory.KEY_FILE).toString()))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThatThrownBy(()->service.preview(root.resolve(first.targetPath()).toString()))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            var second=service.preview(source.toString());assertThat(second.targetPath()).isNotEqualTo(first.targetPath());
            service.commit(source.toString(),second);assertThat(root.resolve(second.targetPath())).hasContent("source-retained");
            assertThatThrownBy(()->service.commit(source.toString(),first)).isInstanceOf(RuntimeException.class);
            assertThat(root.resolve(first.targetPath())).hasContent("source-retained");
        }
    }
    @Test void changedOrOccupiedSourceAndWrongOwnerTargetNeverPublish() throws Exception {
        try(var ctx=start()) {
            var service=ctx.getBean(NativeInboxService.class);Path source=Files.writeString(temp.resolve("snapshot.txt"),"old-bytes");var preview=service.preview(source.toString());
            Files.writeString(source,"new-bytes");assertThatThrownBy(()->service.commit(source.toString(),preview)).isInstanceOf(RuntimeException.class);
            assertThat(ctx.getBean(DesktopDataDirectory.class).libraryRoot().resolve(preview.targetPath())).doesNotExist();
            try(var channel=FileChannel.open(source,StandardOpenOption.WRITE);var lock=channel.lock()) {assertThatThrownBy(()->service.preview(source.toString())).isInstanceOf(RuntimeException.class);}
            long ownerB=ctx.getBean(AppUserRepository.class).saveAndFlush(new AppUser("native-other","disabled",AuthRole.USER)).getId();TenantContext.set(ownerB);
            assertThatThrownBy(()->service.commit(source.toString(),preview)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThat(source).hasContent("new-bytes");
        }
    }
    @Test void nativeCapabilityCannotBeReplacedByAUserSessionOrLaunchToken() {
        try(var ctx=start()) {
            var controller=new NativeInboxController(ctx.getBean(NativeInboxService.class),"a".repeat(64));
            assertThatThrownBy(()->controller.preview(null,new NativeInboxController.Selection(temp.resolve("private.txt").toString()))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThatThrownBy(()->controller.preview("b".repeat(64),new NativeInboxController.Selection(temp.resolve("private.txt").toString()))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }
    }
    private void confirm(ConfigurableApplicationContext ctx, long id, String target) {
        var snapshot=new com.coffer.model.runtime.ModelExecutionContext.Snapshot(java.util.UUID.randomUUID().toString(),TenantContext.requireOwnerId(),"io-fixture",com.coffer.governance.domain.GovernanceRunMode.LOCAL,java.util.Map.of());
        com.coffer.model.runtime.ModelExecutionContext.with(snapshot,()->ctx.getBean(com.coffer.inbox.application.InboxImportScanner.class).confirm(id,target));
    }
    @Test void sourceIsDeletedOnlyAfterFormalMetadataAndVerifiedBodyCommit() throws Exception {
        try(var ctx=start()) {
            var service=ctx.getBean(NativeInboxService.class);
            Path source=Files.writeString(temp.resolve("handoff.txt"),"owned-body");var preview=service.preview(source.toString());
            Path other=Files.writeString(Files.createDirectory(temp.resolve("other")).resolve("handoff.txt"),"owned-body");var otherPreview=service.preview(other.toString());
            assertThat(otherPreview.targetPath()).isEqualTo(preview.targetPath());
            service.commit(source.toString(),preview);
            // A losing/unfinished publication must not inherit the winning copy's deletion consent.
            ctx.getBean(NativeTransferService.class).begin(other,otherPreview);
            ctx.getBean(NativeTransferService.class).recover();assertThat(source).hasContent("owned-body");
            var scanner=ctx.getBean(com.coffer.inbox.application.InboxImportScanner.class);scanner.scanOnce();
            var row=ctx.getBean(com.coffer.inbox.infrastructure.persistence.InboxImportRecordRepository.class).findAll().get(0);
            confirm(ctx,row.getId(),row.getTargetPath());
            assertThat(source).doesNotExist();
            assertThat(other).hasContent("owned-body");
            var file=ctx.getBean(com.coffer.file.infrastructure.persistence.FileMetadataRepository.class).findAll().get(0);
            assertThat(ctx.getBean(DesktopDataDirectory.class).libraryRoot().resolve(file.getStoragePath())).hasContent("owned-body");
            assertThat(ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class).queryForObject("SELECT state FROM desktop_native_transfer WHERE source_path=?",String.class,source.toString())).isEqualTo("COMPLETED");
            confirm(ctx,row.getId(),row.getTargetPath());
        }
    }
    @Test void changedExternalSourceIsRetainedWithVisibleWarningAfterSuccessfulImport() throws Exception {
        try(var ctx=start()) {
            var service=ctx.getBean(NativeInboxService.class);
            Path source=Files.writeString(temp.resolve("handoff.txt"),"old-body");var preview=service.preview(source.toString());
            service.commit(source.toString(),preview);Files.writeString(source,"new-body");
            var scanner=ctx.getBean(com.coffer.inbox.application.InboxImportScanner.class);scanner.scanOnce();
            var rows=ctx.getBean(com.coffer.inbox.infrastructure.persistence.InboxImportRecordRepository.class);var row=rows.findAll().get(0);
            confirm(ctx,row.getId(),row.getTargetPath());
            assertThat(source).hasContent("new-body");
            assertThat(rows.findById(row.getId()).orElseThrow().getLastError()).contains("来源保留");
            assertThat(ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class).queryForObject("SELECT state FROM desktop_native_transfer",String.class)).isEqualTo("RETAINED");
        }
    }
}
