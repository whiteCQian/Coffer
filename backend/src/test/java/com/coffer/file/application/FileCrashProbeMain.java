package com.coffer.file.application;

import com.coffer.CofferApplication;
import com.coffer.auth.domain.AppUser;
import com.coffer.auth.domain.AuthRole;
import com.coffer.auth.infrastructure.AppUserRepository;
import com.coffer.auth.service.TenantContext;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.file.domain.FileRenameIntentStatus;
import com.coffer.file.domain.FileWriteIntentStatus;
import com.coffer.file.domain.StorageDeletionStatus;
import com.coffer.file.domain.WorkSaveStatus;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.infrastructure.persistence.FileRenameIntentRepository;
import com.coffer.file.infrastructure.persistence.FileWriteIntentRepository;
import com.coffer.file.infrastructure.persistence.StorageDeletionTaskRepository;
import com.coffer.file.infrastructure.persistence.WorkSaveIntentRepository;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.governance.domain.GovernanceRunMode;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Manually invoked by scripts/verify-file-crash-recovery.ps1. The first JVM
 * uses Runtime.halt after a durable step; a fresh JVM checks startup recovery.
 */
public final class FileCrashProbeMain {
    private static final String USERNAME = "file-crash-probe-owner";
    private static final String OPERATION = "125ce6a2-3733-45f9-8bc5-c3a1d184c9ae";
    private static final String TASK = "1ef26066-e2ed-483a-80c9-6db4e0dc5b71";
    private static final String NEW_TASK = "4ee5d080-2421-476e-9bd5-4fa1f36a2700";
    private static final byte[] BODY = "crash-safe-original".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EDITED = "crash-safe-edited-version".getBytes(StandardCharsets.UTF_8);

    private FileCrashProbeMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !("crash".equals(args[0]) || "verify".equals(args[0])))
            throw new IllegalArgumentException("usage: crash|verify prepared|object|metadata|rename|work-*|delete-*|orphan-*");
        String step = args[1];
        if (!java.util.Set.of("prepared", "object", "metadata", "rename",
                "work-prepared", "work-published", "work-recorded", "work-committed",
                "delete-enqueued", "delete-claimed", "delete-removed",
                "orphan-discard-pending", "orphan-discard-removed").contains(step))
            throw new IllegalArgumentException("unknown crash step");

        try (var context = new SpringApplicationBuilder(CofferApplication.class)
                .profiles("desktop")
                .properties("spring.main.banner-mode=off")
                .run("--server.port=0", "--spring.profiles.active=desktop",
                        "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=1",
                        "--spring.data.redis.connect-timeout=100ms", "--spring.data.redis.timeout=100ms",
                        "--minio.endpoint=http://127.0.0.1:1", "--coffer.import.inbox.enabled=false", "--coffer.embedding.enabled=false",
                        "--coffer.desktop.initialize=" + "crash".equals(args[0]))) {
            AppUserRepository users = context.getBean(AppUserRepository.class);
            Long owner = "crash".equals(args[0])
                    ? users.saveAndFlush(new AppUser(USERNAME, "disabled-test-login", AuthRole.USER)).getId()
                    : users.findByUsername(USERNAME).orElseThrow().getId();
            TenantContext.set(owner);
            try {
                String key = "users/" + owner + "/files/crash-probe.txt";
                FileStoragePort storage = context.getBean(FileStoragePort.class);
                FileWriteIntentService intents = context.getBean(FileWriteIntentService.class);
                FileMetadataRepository files = context.getBean(FileMetadataRepository.class);
                if ("rename".equals(step)) {
                    verifyRenameCrash(context, args[0], key, storage, files);
                    return;
                }
                if (step.startsWith("orphan-")) {
                    verifyOrphanDiscardCrash(context, args[0], step, key, storage, files);
                    return;
                }
                if (step.startsWith("work-")) {
                    verifyWorkSaveCrash(context, args[0], step, key, storage, files);
                    return;
                }
                if (step.startsWith("delete-")) {
                    verifyDeleteCrash(context, args[0], step, key, storage, files);
                    return;
                }
                if ("crash".equals(args[0])) {
                    intents.begin(OPERATION, TASK, "UPLOAD", key, "crash-probe.txt", "txt",
                            "text/plain", BODY.length, null);
                    if (!"prepared".equals(step)) {
                        var object = storage.write(key, new ByteArrayInputStream(BODY), "text/plain", BODY.length);
                        intents.objectWritten(OPERATION, object);
                        if ("metadata".equals(step)) {
                            files.saveAndFlush(FileMetadata.builder().fileName("crash-probe.txt")
                                    .fileType("txt").fileSize((long) BODY.length).storagePath(key)
                                    .contentSha256(object.sha256()).contentEtag(object.etag())
                                    .taskId(TASK).status(FileStatus.FAILED).build());
                        }
                    }
                    System.out.println("CRASH_PROBE_HALTING=" + step);
                    System.out.flush();
                    Runtime.getRuntime().halt(73);
                    return;
                }

                FileWriteIntentRepository rows = context.getBean(FileWriteIntentRepository.class);
                var intent = rows.findById(OPERATION).orElseThrow();
                FileWriteIntentStatus expected = switch (step) {
                    case "prepared" -> FileWriteIntentStatus.ABORTED;
                    case "object" -> FileWriteIntentStatus.MANUAL_REVIEW;
                    default -> FileWriteIntentStatus.REGISTERED;
                };
                if (intent.getStatus() != expected)
                    throw new AssertionError("recovery status " + intent.getStatus() + " != " + expected);
                if ("prepared".equals(step)) {
                    if (storage.exists(key) || files.findByTaskId(TASK).isPresent())
                        throw new AssertionError("unpublished file became visible");
                } else if ("object".equals(step)) {
                    if (files.findByTaskId(TASK).isPresent() || !storage.exists(key))
                        throw new AssertionError("orphan object was lost or exposed");
                    Long restoredId = context.getBean(FileWriteIntentRecovery.class).attachOrphan(OPERATION);
                    FileMetadata restored = files.findById(restoredId).orElseThrow();
                    if (restored.getStatus() != FileStatus.FAILED)
                        throw new AssertionError("restored object is not a visible failed file");
                } else {
                    FileMetadata file = files.findByTaskId(TASK).orElseThrow();
                    if (!Objects.equals(file.getContentSha256(), storage.stat(key).sha256()))
                        throw new AssertionError("registered file changed after restart");
                }
                if (!"prepared".equals(step)
                        && !java.util.Arrays.equals(BODY, storage.read(key).readAllBytes()))
                    throw new AssertionError("published bytes changed after restart");
                System.out.println("CRASH_PROBE_RECOVERED=" + step);
                System.out.flush();
            } finally {
                TenantContext.clear();
            }
        }
    }

    private static void verifyRenameCrash(org.springframework.context.ApplicationContext context,
                                          String phase, String key, FileStoragePort storage,
                                          FileMetadataRepository files) throws Exception {
        FileRenameIntentService renames = context.getBean(FileRenameIntentService.class);
        if ("crash".equals(phase)) {
            var object = storage.write(key, new ByteArrayInputStream(BODY), "text/plain", BODY.length);
            FileMetadata file = files.saveAndFlush(FileMetadata.builder().fileName("crash-probe.txt")
                    .fileType("txt").fileSize((long) BODY.length).storagePath(key)
                    .contentSha256(object.sha256()).contentEtag(object.etag())
                    .taskId(TASK).revision(0L).status(FileStatus.COMPLETED).build());
            renames.prepare(file, "renamed.txt");
            System.out.println("CRASH_PROBE_HALTING=rename");
            System.out.flush();
            Runtime.getRuntime().halt(73);
            return;
        }

        FileMetadata file = files.findByTaskId(TASK).orElseThrow();
        if (!"renamed.txt".equals(file.getFileName()) || file.getRevision() != 1L)
            throw new AssertionError("committed rename intent did not replay on startup");
        var renameRows = context.getBean(FileRenameIntentRepository.class).findAll();
        if (renameRows.size() != 1 || renameRows.get(0).getStatus() != FileRenameIntentStatus.APPLIED)
            throw new AssertionError("rename recovery ledger is not applied");
        if (!java.util.Arrays.equals(BODY, storage.read(key).readAllBytes()))
            throw new AssertionError("rename changed the original bytes");
        System.out.println("CRASH_PROBE_RECOVERED=rename");
        System.out.flush();
    }

    private static void verifyWorkSaveCrash(org.springframework.context.ApplicationContext context,
                                            String phase, String step, String key,
                                            FileStoragePort storage, FileMetadataRepository files) throws Exception {
        String target = key.substring(0, key.lastIndexOf('/') + 1) + "crash-probe-edited.txt";
        WorkSaveIntentService saves = context.getBean(WorkSaveIntentService.class);
        String editedSha = sha256(EDITED);
        if ("crash".equals(phase)) {
            var original = storage.write(key, new ByteArrayInputStream(BODY), "text/plain", BODY.length);
            FileMetadata file = files.saveAndFlush(FileMetadata.builder().fileName("crash-probe.txt")
                    .fileType("txt").fileSize((long) BODY.length).storagePath(key)
                    .contentSha256(original.sha256()).contentEtag(original.etag())
                    .taskId(TASK).revision(0L).status(FileStatus.COMPLETED).build());
            saves.prepare(OPERATION, file, 0L, original.sha256(), target, EDITED.length,
                    editedSha, NEW_TASK, "a2df5059-cb36-4180-9bb4-de678dcaa191", GovernanceRunMode.LOCAL);
            if (!"work-prepared".equals(step)) {
                var replacement = storage.write(target, new ByteArrayInputStream(EDITED),
                        "text/plain", EDITED.length);
                if (!"work-published".equals(step)) {
                    saves.objectWritten(OPERATION, replacement);
                    if ("work-committed".equals(step) && !saves.commit(OPERATION))
                        throw new AssertionError("work save did not commit before crash");
                }
            }
            System.out.println("CRASH_PROBE_HALTING=" + step);
            System.out.flush();
            Runtime.getRuntime().halt(73);
            return;
        }

        var work = context.getBean(WorkSaveIntentRepository.class).findById(OPERATION).orElseThrow();
        var storedFiles = files.findAll();
        if (storedFiles.size() != 1) throw new AssertionError("work save duplicated the formal file");
        FileMetadata file = storedFiles.get(0);
        if (!Arrays.equals(BODY, storage.read(key).readAllBytes()))
            throw new AssertionError("work save lost the retained original");
        if ("work-prepared".equals(step)) {
            if (work.getStatus() != WorkSaveStatus.ABORTED || file.getRevision() != 0L
                    || !key.equals(file.getStoragePath()) || storage.exists(target))
                throw new AssertionError("unpublished work copy changed the formal file");
        } else {
            if (work.getStatus() != WorkSaveStatus.COMMITTED || file.getRevision() != 1L
                    || !target.equals(file.getStoragePath())
                    || !editedSha.equals(file.getContentSha256())
                    || !Arrays.equals(EDITED, storage.read(target).readAllBytes()))
                throw new AssertionError("published work copy did not become one formal revision");
            var cleanups = context.getBean(StorageDeletionTaskRepository.class).findAll();
            if (cleanups.size() != 1 || !key.equals(cleanups.get(0).getObjectPath()))
                throw new AssertionError("retained old revision has no durable cleanup task");
        }
        System.out.println("CRASH_PROBE_RECOVERED=" + step);
        System.out.flush();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void verifyOrphanDiscardCrash(org.springframework.context.ApplicationContext context,
                                                 String phase, String step, String key,
                                                 FileStoragePort storage, FileMetadataRepository files) {
        FileWriteIntentRepository rows = context.getBean(FileWriteIntentRepository.class);
        FileWriteIntentService intents = context.getBean(FileWriteIntentService.class);
        FileWriteIntentRecovery recovery = context.getBean(FileWriteIntentRecovery.class);
        if ("crash".equals(phase)) {
            var object = storage.write(key, new ByteArrayInputStream(BODY), "text/plain", BODY.length);
            intents.recordUnknownOrphan(object);
            String id = rows.findAll().get(0).getId();
            recovery.requestOrphanDiscard(id, object.sha256());
            var row = rows.findById(id).orElseThrow();
            row.setRetentionUntil(java.time.LocalDateTime.now().minusMinutes(1));
            row.setNextAttemptAt(java.time.LocalDateTime.now().minusMinutes(1));
            rows.saveAndFlush(row);
            if ("orphan-discard-removed".equals(step)) {
                if (intents.claimDiscard(id) == null) throw new AssertionError("orphan cleanup was not claimed");
                storage.delete(key, object.sha256());
            }
            System.out.println("CRASH_PROBE_HALTING=" + step);
            System.out.flush();
            Runtime.getRuntime().halt(73);
            return;
        }
        var row = rows.findAll().get(0);
        if (row.getStatus() != FileWriteIntentStatus.DISCARDED
                || row.getDiscardedAt() == null || storage.exists(key)
                || !files.findAll().isEmpty())
            throw new AssertionError("orphan disposition did not recover a terminal audit state");
        System.out.println("CRASH_PROBE_RECOVERED=" + step);
        System.out.flush();
    }

    private static void verifyDeleteCrash(org.springframework.context.ApplicationContext context,
                                          String phase, String step, String key,
                                          FileStoragePort storage, FileMetadataRepository files) throws Exception {
        StorageDeletionTaskRepository rows = context.getBean(StorageDeletionTaskRepository.class);
        if ("crash".equals(phase)) {
            var object = storage.write(key, new ByteArrayInputStream(BODY), "text/plain", BODY.length);
            FileMetadata file = files.saveAndFlush(FileMetadata.builder().fileName("crash-probe.txt")
                    .fileType("txt").fileSize((long) BODY.length).storagePath(key)
                    .contentSha256(object.sha256()).contentEtag(object.etag())
                    .taskId(TASK).revision(0L).status(FileStatus.COMPLETED).build());
            context.getBean(FileOperationService.class).deleteFile(file.getId());
            var cleanups = rows.findAll();
            if (cleanups.size() != 1) throw new AssertionError("delete intent was not committed");
            if (!"delete-enqueued".equals(step)) {
                var claimed = context.getBean(StorageDeletionTaskService.class).claim(cleanups.get(0).getId());
                if (claimed == null) throw new AssertionError("delete task was not claimed");
                if ("delete-removed".equals(step)) storage.delete(key, object.sha256());
            }
            System.out.println("CRASH_PROBE_HALTING=" + step);
            System.out.flush();
            Runtime.getRuntime().halt(73);
            return;
        }

        var cleanups = rows.findAll();
        if (cleanups.size() != 1 || cleanups.get(0).getStatus() != StorageDeletionStatus.SUCCEEDED
                || cleanups.get(0).getDeletedAt() == null || !files.findAll().isEmpty()
                || storage.exists(key))
            throw new AssertionError("deletion did not recover to a verified terminal state");
        System.out.println("CRASH_PROBE_RECOVERED=" + step);
        System.out.flush();
    }
}
