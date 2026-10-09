package com.coffer.desktop;

import com.coffer.CofferApplication;
import com.coffer.auth.domain.*;
import com.coffer.auth.infrastructure.AppUserRepository;
import com.coffer.auth.service.TenantContext;
import com.coffer.file.domain.*;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.infrastructure.storage.PathGenerator;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.model.runtime.ModelExecutionContext;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.nio.file.*;
import java.util.*;

/** Optional installed-Word COM test, coordinated by verify-work-copy-office.ps1 in an isolated workspace. */
public class WorkCopyOfficeProbeMain {
    public static void main(String[] args) throws Exception {
        Path signals = Path.of(args[0]);
        try (var ctx = new SpringApplicationBuilder(CofferApplication.class).run("--spring.profiles.active=desktop", "--server.port=0",
                "--coffer.desktop.initialize=true", "--coffer.import.inbox.enabled=false", "--coffer.embedding.enabled=false",
                "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=1", "--minio.endpoint=http://127.0.0.1:1")) {
            ctx.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().forEach(org.springframework.scheduling.config.ScheduledTask::cancel);
            long owner = ctx.getBean(AppUserRepository.class).saveAndFlush(new AppUser("r33-office", "disabled", AuthRole.USER)).getId(); TenantContext.set(owner);
            var storage = ctx.getBean(FileStoragePort.class); var files = ctx.getBean(FileMetadataRepository.class);
            String key = ctx.getBean(PathGenerator.class).generateStoragePath("Office验收.docx", "docx");
            Path fixture = signals.resolve("original.docx"); FileStoragePort.StoredObject original;
            try (var input = Files.newInputStream(fixture)) { original = storage.write(key, input, null, Files.size(fixture)); }
            var file = files.saveAndFlush(FileMetadata.builder().fileName("Office验收.docx").fileType("docx").fileSize(original.size())
                    .storagePath(key).contentSha256(original.sha256()).status(FileStatus.COMPLETED).revision(0L).build());
            var copies = ctx.getBean(WorkCopyService.class); var copy = copies.create(file.getId());
            Files.writeString(signals.resolve("work-path.txt"), copy.workPath());
            waitFor(signals.resolve("busy.flag"));
            if (!copies.get(copy.id()).busy()) throw new AssertionError("real Word lock not detected");
            var blocked = submit(copies, copy.id(), owner);
            if (!blocked.status().equals("INTERRUPTED") || !blocked.errorCode().equals("COPY_BUSY_OR_CHANGED")) throw new AssertionError("busy save accepted");
            if (!storage.stat(key).sha256().equals(original.sha256())) throw new AssertionError("Word wrote formal bytes");
            Files.writeString(signals.resolve("busy-pass.flag"), "PASS");
            waitFor(signals.resolve("crashed.flag"));
            copies.close(copy.id(), "REPORT_EXIT", null);
            if (!copies.get(copy.id()).errorCode().equals("APP_EXIT_UNCONFIRMED")) throw new AssertionError("crash not visible");
            try (var input = Files.newInputStream(Path.of(copy.workPath())); var document = new org.apache.poi.xwpf.usermodel.XWPFDocument(input)) {
                String text = document.getParagraphs().stream().map(org.apache.poi.xwpf.usermodel.XWPFParagraph::getText).reduce("", (a,b) -> a+b);
                if (!text.contains("R33 real Word saved edit") || text.contains("memory only unsaved")) throw new AssertionError("disk/memory exit evidence wrong");
            }
            Files.writeString(signals.resolve("crash-pass.flag"), "PASS");
            waitFor(signals.resolve("closed.flag"));
            if (copies.get(copy.id()).busy()) throw new AssertionError("own Word cleanup did not release the copy");
            var saved = submit(copies, copy.id(), owner);
            if (!saved.status().equals("SAVED") || files.findById(file.getId()).orElseThrow().getRevision() != 1L) throw new AssertionError("real docx save failed");
            if (!copies.close(copy.id(), "CLOSE", saved.sha256()).status().equals("CLOSED")) throw new AssertionError("real docx close failed");
            if (!storage.stat(key).sha256().equals(original.sha256())) throw new AssertionError("retained original changed");
            System.out.println("R33_OFFICE_PASS: real DOCX, Word lock, unsaved Word process crash, visible interruption, retained formal, disk-only recovery, version save and close");
            TenantContext.clear();
        }
    }
    private static WorkCopyService.View submit(WorkCopyService copies, String id, long owner) {
        return ModelExecutionContext.with(new ModelExecutionContext.Snapshot("r33-office-fixture", owner, "fixture", GovernanceRunMode.LOCAL, Map.of()), () -> copies.save(id));
    }
    private static void waitFor(Path signal) throws Exception {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
        while (!Files.exists(signal)) { if (System.nanoTime() > until) throw new IllegalStateException("Office fixture coordination timeout: " + signal.getFileName()); Thread.sleep(100); }
    }
}
