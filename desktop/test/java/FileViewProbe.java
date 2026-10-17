package com.coffer.desktop;
import com.coffer.CofferApplication;
import com.coffer.auth.domain.*;
import com.coffer.auth.infrastructure.AppUserRepository;
import com.coffer.auth.service.*;
import com.coffer.file.domain.*;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.infrastructure.storage.PathGenerator;
import com.coffer.file.storage.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.io.*;
import java.util.*;

/** Runs against the exact shipped backend classes; all files/accounts and OS handoffs are fixtures. */
public class FileViewProbe {
    static final List<Path> opened=new ArrayList<>();static boolean fail;
    @Configuration(proxyBeanMethods=false) public static class Fixtures {
        @Bean @Primary WorkCopyOpener fixtureOpener(){return new WorkCopyOpener(){@Override public void open(Path path)throws IOException{if(fail)throw new IOException("fixture association unavailable");opened.add(path);}};}
    }
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    static FileMetadata seed(org.springframework.context.ConfigurableApplicationContext ctx,String name,String type,String body){
        byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String key=ctx.getBean(PathGenerator.class).generateStoragePath(name,type);
        var object=ctx.getBean(FileStoragePort.class).write(key,new ByteArrayInputStream(bytes),null,bytes.length);
        return ctx.getBean(FileMetadataRepository.class).saveAndFlush(FileMetadata.builder().fileName(name).fileType(type).fileSize((long)bytes.length).contentSha256(object.sha256()).storagePath(key).status(FileStatus.COMPLETED).revision(3L).build());
    }
    static void writable(Path file)throws Exception{Files.setAttribute(file,"dos:readonly",false,LinkOption.NOFOLLOW_LINKS);}
    public static void main(String[] args)throws Exception{
        try(var ctx=new SpringApplicationBuilder(CofferApplication.class,Fixtures.class).headless(false).run(
          "--spring.profiles.active=desktop","--coffer.desktop.data-directory="+args[0],"--coffer.desktop.initialize=true","--server.port=0","--spring.main.banner-mode=off",
          "--coffer.embedding.enabled=false","--coffer.import.inbox.enabled=false","--coffer.parser.process-isolation=false","--logging.config="+Path.of(args[0]).getParent().resolve("probe-logback.xml").toUri())){
            ctx.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().forEach(org.springframework.scheduling.config.ScheduledTask::cancel);
            var users=ctx.getBean(AppUserRepository.class);long owner=users.saveAndFlush(new AppUser("view-probe-a","disabled",AuthRole.USER)).getId();TenantContext.set(owner);
            var file=seed(ctx,"../虚构报告?.txt","txt","fictional-original");
            var views=ctx.getBean(FileViewService.class);var copies=ctx.getBean(WorkCopyRepository.class);long count=copies.count();
            check(views.open(file.getId(),3L).status().equals("OPENED"),"view succeeds");Path first=opened.get(0);
            check(first.startsWith(ctx.getBean(DesktopDataDirectory.class).root().resolve(".coffer-runtime/view-cache")),"OS only receives viewing-cache path");
            check(first.getFileName().toString().endsWith(".txt")&&!first.getFileName().toString().contains("?"),"safe display name and extension");
            views.open(file.getId(),null);check(opened.get(1).equals(first),"unchanged current version reuses cache");
            writable(first);Files.writeString(first,"fictional-cache-edit");views.open(file.getId(),null);
            check(!opened.get(2).equals(first),"changed viewing copy is not reused or overwritten");check(Files.readString(first).equals("fictional-cache-edit"),"cache edits retained");
            Path formal=ctx.getBean(DesktopDataDirectory.class).libraryRoot().resolve(file.getStoragePath());check(Files.readString(formal).equals("fictional-original"),"formal content unchanged");
            int before=opened.size();try{views.open(file.getId(),2L);throw new AssertionError("stale revision accepted");}catch(com.coffer.service.FileVersionConflictException expected){}check(opened.size()==before,"stale request does not launch");
            fail=true;check(views.open(file.getId(),null).errorCode().equals("APP_OPEN_FAILED"),"association failure explained");fail=false;
            var executable=seed(ctx,"fixture.exe","exe","fictional-not-executable");check(views.open(executable.getId(),null).errorCode().equals("UNSUPPORTED_TYPE"),"executable is never launched");
            check(copies.count()==count,"viewing never creates work-copy records");
            long second=users.saveAndFlush(new AppUser("view-probe-b","disabled",AuthRole.USER)).getId();TenantContext.set(second);
            try{views.open(file.getId(),null);throw new AssertionError("other owner viewed file");}catch(ResourceNotFoundException expected){}
            TenantContext.set(owner);
            var controller=new FileViewController(views,"0".repeat(64));
            try{controller.open(file.getId(),null,null);throw new AssertionError("missing capability accepted");}catch(org.springframework.security.access.AccessDeniedException expected){}
            controller.open(file.getId(),new FileViewController.Request(3L),"0".repeat(64));
            var repo=ctx.getBean(FileMetadataRepository.class);file.setRevision(4L);repo.saveAndFlush(file);views.open(file.getId(),4L);check(!opened.get(opened.size()-1).equals(opened.get(2)),"new revision gets new cache");
            Path cacheRoot=first.getParent().getParent();
            String sha=file.getContentSha256();Path old=Files.createDirectory(cacheRoot.resolve("f99-r1-"+sha+"-"+UUID.randomUUID()));Path oldFile=Files.writeString(old.resolve("fixture.txt"),"fictional-original");Files.setLastModifiedTime(old,FileTime.fromMillis(System.currentTimeMillis()-10L*86400000));
            var isolated=new FileViewService(repo,ctx.getBean(FileStoragePort.class),ctx.getBean(DesktopDataDirectory.class),ctx.getBean(WorkCopyOpener.class));isolated.cleanup(cacheRoot);check(!Files.exists(old),"old unchanged unoccupied cache reclaimed");
            Files.setLastModifiedTime(first.getParent(),FileTime.fromMillis(System.currentTimeMillis()-10L*86400000));isolated.cleanup(cacheRoot);check(Files.exists(first),"modified old viewing copy retained");
            System.out.println("FILE_VIEW_PROBE_PASS: cache integrity/reuse, formal protection, current revision, failure, type allowlist, owner isolation, native capability, no edit records, retention");
        }finally{TenantContext.clear();}
    }
}
