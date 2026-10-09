package com.coffer.desktop;

import com.coffer.CofferApplication;
import com.coffer.auth.domain.*;
import com.coffer.auth.infrastructure.AppUserRepository;
import com.coffer.auth.service.TenantContext;
import com.coffer.file.application.*;
import com.coffer.file.domain.*;
import com.coffer.file.infrastructure.persistence.*;
import com.coffer.file.infrastructure.storage.PathGenerator;
import com.coffer.file.storage.FileStoragePort;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DesktopBackupIntegrationTest {
    @TempDir Path temp;private static final char[] PASSWORD="R35-strong-restore-passphrase".toCharArray();
    record Sample(long ownerA,long ownerB,long fileA,long fileB,String workId,String orphanId) { }
    @AfterEach void clear(){TenantContext.clear();var file=((ch.qos.logback.classic.LoggerContext)org.slf4j.LoggerFactory.getILoggerFactory()).getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("FILE");if(file instanceof ch.qos.logback.core.FileAppender<?> output && Path.of(output.getFile()).startsWith(temp))output.stop();}
    private ConfigurableApplicationContext start(Path root,boolean initialize) {
        var context=new SpringApplicationBuilder(CofferApplication.class).run("--spring.profiles.active=desktop","--coffer.desktop.data-directory="+root,
                "--coffer.desktop.initialize="+initialize,"--server.port=0","--coffer.import.inbox.enabled=false","--coffer.embedding.enabled=false","--spring.data.redis.port=1");
        context.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().forEach(org.springframework.scheduling.config.ScheduledTask::cancel);return context;
    }
    private FileMetadata file(ConfigurableApplicationContext context,String name,String content) {
        byte[] bytes=content.getBytes(java.nio.charset.StandardCharsets.UTF_8);String key=context.getBean(PathGenerator.class).generateStoragePath(name,"txt");
        var object=context.getBean(FileStoragePort.class).write(key,new ByteArrayInputStream(bytes),null,bytes.length);
        return context.getBean(FileMetadataRepository.class).saveAndFlush(FileMetadata.builder().fileName(name).fileType("txt").fileSize(object.size()).contentSha256(object.sha256()).storagePath(key).revision(4L).status(FileStatus.COMPLETED).build());
    }
    private Sample seed(Path root) throws Exception {
        try(var context=start(root,true)) {
            var accounts=context.getBean(AppUserRepository.class);var encoder=context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class);
            long a=accounts.saveAndFlush(new AppUser("r35-user-a",encoder.encode("R35-user-pass"),AuthRole.USER)).getId();
            long b=accounts.saveAndFlush(new AppUser("r35-user-b",encoder.encode("R35-user-pass"),AuthRole.USER)).getId();
            TenantContext.set(a);var first=file(context,"中文合同.txt","用户甲正式正文");
            var copy=context.getBean(WorkCopyService.class).create(first.getId());Files.writeString(Path.of(copy.workPath()),"未提交的中文工作副本");
            var record=context.getBean(WorkCopyRepository.class).findById(copy.id()).orElseThrow();record.setStatus("OPENED");context.getBean(WorkCopyRepository.class).saveAndFlush(record);
            String operation=UUID.randomUUID().toString(),task=UUID.randomUUID().toString(),key=context.getBean(PathGenerator.class).generateStoragePath("未完成上传.txt","txt");
            byte[] orphan="已发布但尚未登记的正文".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var intents=context.getBean(FileWriteIntentService.class);intents.begin(operation,task,"UPLOAD",key,"未完成上传.txt","txt","text/plain",orphan.length,null);
            intents.objectWritten(operation,context.getBean(FileStoragePort.class).write(key,new ByteArrayInputStream(orphan),null,orphan.length));
            TenantContext.set(b);var second=file(context,"发票样本.txt","用户乙正式正文");return new Sample(a,b,first.getId(),second.getId(),copy.id(),operation);
        } finally{TenantContext.clear();}
    }
    @Test void wholeEncryptedBackupRestoresBothAccountsChineseFilesAndPendingOperationsToANewDataRoot() throws Exception {
        Path source=temp.resolve("source");var sample=seed(source);Path media=Files.createDirectory(temp.resolve("local-snapshots"));var service=new DesktopBackupService();
        String originalKey=Files.readString(source.resolve(DesktopDataDirectory.KEY_FILE));
        var backup=service.backup(source,source.resolve("library"),media,PASSWORD,true,Map.of("applicationVersion","0.1.0"));
        assertThat(backup.status()).isEqualTo("LOCAL_SNAPSHOT_VERIFIED");assertThat(backup.independent()).isFalse();assertThat(backup.users()).isEqualTo(2);assertThat(backup.files()).isEqualTo(2);
        byte[] ciphertext=Files.readAllBytes(Path.of(backup.packagePath()));assertThat(new String(ciphertext,java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(originalKey,"r35-user-a");
        Path target=temp.resolve("new-machine-data");assertThat(service.restore(Path.of(backup.packagePath()),PASSWORD,target).status()).isEqualTo("RESTORED_VERIFIED");
        assertThat(target.resolve(DesktopDataDirectory.KEY_FILE)).hasContent(originalKey);
        try(var restored=start(target,false)) {
            var accounts=restored.getBean(AppUserRepository.class);var encoder=restored.getBean(org.springframework.security.crypto.password.PasswordEncoder.class);
            assertThat(encoder.matches("R35-user-pass",accounts.findByUsername("r35-user-a").orElseThrow().getPasswordHash())).isTrue();
            assertThat(encoder.matches("R35-user-pass",accounts.findByUsername("r35-user-b").orElseThrow().getPasswordHash())).isTrue();
            TenantContext.set(sample.ownerA());var files=restored.getBean(FileMetadataRepository.class);assertThat(files.findById(sample.fileA())).isPresent();assertThat(files.findById(sample.fileB())).isEmpty();
            var copy=restored.getBean(WorkCopyService.class).get(sample.workId());assertThat(copy.status()).isEqualTo("INTERRUPTED");assertThat(Path.of(copy.workPath())).hasContent("未提交的中文工作副本");
            assertThat(restored.getBean(FileWriteIntentRepository.class).findById(sample.orphanId())).isPresent();
            Long recovered=restored.getBean(FileWriteIntentRecovery.class).attachOrphan(sample.orphanId());assertThat(files.findById(recovered)).isPresent();
            TenantContext.set(sample.ownerB());assertThat(files.findById(sample.fileB())).isPresent();assertThat(files.findById(sample.fileA())).isEmpty();
        }
    }
    @Test void missingKeyOrFormalBodyRefusesBackupAndWrongPasswordNeverCreatesANewLibrary() throws Exception {
        Path source=temp.resolve("source");seed(source);var service=new DesktopBackupService();Path media=Files.createDirectory(temp.resolve("snapshots"));
        var backup=service.backup(source,source.resolve("library"),media,PASSWORD,true,Map.of());Path target=temp.resolve("failed-restore");
        assertThatThrownBy(()->service.restore(Path.of(backup.packagePath()),"incorrect-password-long".toCharArray(),target)).isInstanceOf(Exception.class);assertThat(target).doesNotExist();
        Path key=source.resolve(DesktopDataDirectory.KEY_FILE),saved=source.resolve("saved-key");Files.move(key,saved);
        assertThatThrownBy(()->service.backup(source,source.resolve("library"),media,PASSWORD,true,Map.of())).isInstanceOf(Exception.class);Files.move(saved,key);
        try(var paths=Files.walk(source.resolve("library"))) {Path body=paths.filter(path->path.getFileName().toString().endsWith(".txt") && path.toString().contains("managed")).findFirst().orElseThrow();Files.delete(body);}
        assertThatThrownBy(()->service.backup(source,source.resolve("library"),media,PASSWORD,true,Map.of())).isInstanceOf(Exception.class);
    }
    @Test void authenticatedButIncompleteArchiveCannotRestoreWithoutItsOriginalKeyOrChineseBody() throws Exception {
        Path source=temp.resolve("source");seed(source);var service=new DesktopBackupService();Path copies=Files.createDirectory(temp.resolve("copies"));
        var backup=service.backup(source,source.resolve("library"),copies,PASSWORD,true,Map.of());
        var plain=new ByteArrayOutputStream();try(var input=Files.newInputStream(Path.of(backup.packagePath()))){BackupCipher.decrypt(input,plain,PASSWORD);}
        for(String missing:List.of("data/"+DesktopDataDirectory.KEY_FILE,"中文合同.txt")) {
            var altered=new ByteArrayOutputStream();
            try(var input=new java.util.zip.ZipInputStream(new ByteArrayInputStream(plain.toByteArray()),java.nio.charset.StandardCharsets.UTF_8);var output=new java.util.zip.ZipOutputStream(altered,java.nio.charset.StandardCharsets.UTF_8)) {
                java.util.zip.ZipEntry entry;boolean removed=false;
                while((entry=input.getNextEntry())!=null){byte[] contents=input.readAllBytes();if(entry.getName().contains(missing) || missing.equals("中文合同.txt") && entry.getName().startsWith("library/") && entry.getName().contains("managed") && !removed){removed=true;continue;}output.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));output.write(contents);output.closeEntry();}
                assertThat(removed).isTrue();
            }
            Path malformed=copies.resolve(UUID.randomUUID()+".cofferbackup");try(var output=BackupCipher.encrypt(Files.newOutputStream(malformed),PASSWORD)){output.write(altered.toByteArray());}
            Path target=temp.resolve("reject-"+UUID.randomUUID());assertThatThrownBy(()->service.restore(malformed,PASSWORD,target)).isInstanceOf(Exception.class);assertThat(target).doesNotExist();
        }
    }
    @Test void normalBackupRejectsSamePhysicalMediaAndRestoreRejectsNonemptyDestination() throws Exception {
        Path source=temp.resolve("source");seed(source);var service=new DesktopBackupService();Path media=Files.createDirectory(temp.resolve("same-disk"));
        assertThatThrownBy(()->service.backup(source,source.resolve("library"),media,PASSWORD,false,Map.of())).hasMessageContaining("INDEPENDENT_MEDIA_REQUIRED");
        var backup=service.backup(source,source.resolve("library"),media,PASSWORD,true,Map.of());Path target=Files.createDirectory(temp.resolve("not-empty"));Files.writeString(target.resolve("existing.txt"),"keep-existing");
        assertThatThrownBy(()->service.restore(Path.of(backup.packagePath()),PASSWORD,target)).hasMessageContaining("TARGET_NOT_EMPTY");assertThat(target.resolve("existing.txt")).hasContent("keep-existing");
    }
}
