package com.coffer.desktop;

import com.coffer.CofferApplication;
import com.coffer.auth.domain.*;
import com.coffer.auth.infrastructure.AppUserRepository;
import com.coffer.auth.service.TenantContext;
import com.coffer.file.application.*;
import com.coffer.file.domain.*;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.infrastructure.storage.PathGenerator;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.model.runtime.ModelExecutionContext;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;

/** Invoked in two different JVMs. The first halts without closing Spring/H2 or releasing library locks. */
public class WorkCopyCrashProbeMain {
    private static final String OPERATION = "d862528b-a643-48fd-b282-b36e6ec83d12";
    public static void main(String[] args) throws Exception {
        String phase = args[0], step = args[1];
        if (!Set.of("copy-opened", "copy-published", "copy-saveas-published", "copy-discard-intent", "copy-save-committed").contains(step)) throw new IllegalArgumentException();
        try (var ctx = new SpringApplicationBuilder(CofferApplication.class).run("--spring.profiles.active=desktop", "--server.port=0",
                "--coffer.desktop.initialize=" + phase.equals("crash"), "--coffer.import.inbox.enabled=false", "--coffer.embedding.enabled=false",
                "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=1", "--minio.endpoint=http://127.0.0.1:1")) {
            ctx.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().forEach(org.springframework.scheduling.config.ScheduledTask::cancel);
            var users = ctx.getBean(AppUserRepository.class);
            long owner = phase.equals("crash") ? users.saveAndFlush(new AppUser("r33-crash", "disabled", AuthRole.USER)).getId()
                    : users.findByUsername("r33-crash").orElseThrow().getId();
            TenantContext.set(owner);
            var copies = ctx.getBean(WorkCopyService.class); var rows = ctx.getBean(WorkCopyRepository.class);
            var files = ctx.getBean(FileMetadataRepository.class); var storage = ctx.getBean(FileStoragePort.class);
            if (phase.equals("crash")) {
                byte[] bytes = "r33-original".getBytes(); String key = ctx.getBean(PathGenerator.class).generateStoragePath("fixture.txt", "txt");
                var object = storage.write(key, new ByteArrayInputStream(bytes), null, bytes.length);
                var file = files.saveAndFlush(FileMetadata.builder().fileName("fixture.txt").fileType("txt").fileSize((long) bytes.length)
                        .storagePath(key).contentSha256(object.sha256()).status(FileStatus.COMPLETED).revision(0L).build());
                var copy = copies.create(file.getId()); Path work = Path.of(copy.workPath()); Files.writeString(work, "r33-edited");
                var row = rows.findById(copy.id()).orElseThrow();
                switch (step) {
                    case "copy-opened" -> row.setStatus("OPENED");
                    case "copy-published" -> { Files.writeString(work, "r33-original"); row.setStatus("PREPARING"); }
                    case "copy-discard-intent" -> { row.setStatus("DISCARDING"); row.setConfirmedSha256(storage.stat(row.getWorkKey()).sha256()); }
                    case "copy-save-committed" -> {
                        ModelExecutionContext.with(new ModelExecutionContext.Snapshot("r33-probe", owner, "fixture", GovernanceRunMode.LOCAL, Map.of()), () -> copies.save(copy.id()));
                        row = rows.findById(copy.id()).orElseThrow();
                        if (!row.getStatus().equals("SAVED")) throw new AssertionError("save failed before halt");
                        row.setStatus("SAVING");
                    }
                    case "copy-saveas-published" -> {
                        String target = "users/" + owner + "/managed/files/save-as.txt";
                        var edited = storage.stat(row.getWorkKey());
                        ctx.getBean(WorkSaveIntentService.class).preparePreserve(OPERATION, file.getId(), file.getFileName(), "txt", key,
                                0, file.getContentSha256(), file.getFileSize(), target, edited.size(), edited.sha256(), UUID.randomUUID().toString());
                        storage.copy(row.getWorkKey(), target, edited.sha256());
                        row.setStatus("SAVING_AS"); row.setOperationId(OPERATION);
                    }
                }
                rows.saveAndFlush(row);
                System.out.println("CRASH_PROBE_HALTING=" + step); System.out.flush(); Runtime.getRuntime().halt(73);
            } else {
                var row = rows.findAll().get(0); var view = copies.get(row.getId());
                Path work = Path.of(view.workPath()); var file = files.findById(row.getFileId()).orElseThrow();
                switch (step) {
                    case "copy-opened", "copy-published" -> {
                        if (!view.status().equals("INTERRUPTED") || !Files.exists(work)) throw new AssertionError("copy disappeared or silent success");
                    }
                    case "copy-discard-intent" -> {
                        if (!view.status().equals("DISCARDED") || Files.exists(work)) throw new AssertionError("discard not reconciled");
                    }
                    case "copy-saveas-published" -> {
                        if (!view.status().equals("SAVED_AS") || view.recoveredFileId() == null || file.getRevision() != 0L
                                || !Files.readString(work).equals("r33-edited")) throw new AssertionError("save-as replaced original or lost copy");
                        if (!storage.stat(files.findById(view.recoveredFileId()).orElseThrow().getStoragePath()).sha256().equals(storage.stat(row.getWorkKey()).sha256())) throw new AssertionError("save-as bytes changed");
                    }
                    case "copy-save-committed" -> {
                        if (!view.status().equals("SAVED") || file.getRevision() != 1L || !Files.readString(work).equals("r33-edited")) throw new AssertionError("committed session not reconciled");
                    }
                }
                String expected = step.equals("copy-save-committed") ? "r33-edited" : "r33-original";
                try (var input = storage.read(file.getStoragePath())) {
                    if (!new String(input.readAllBytes()).equals(expected)) throw new AssertionError("formal bytes changed unexpectedly");
                }
                System.out.println("CRASH_PROBE_RECOVERED=" + step); System.out.flush();
            }
            TenantContext.clear();
        }
    }
}
