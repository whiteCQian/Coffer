package com.coffer.governance;

import com.coffer.CofferApplication;
import com.coffer.auth.domain.AppUser;
import com.coffer.auth.domain.AuthRole;
import com.coffer.auth.infrastructure.AppUserRepository;
import com.coffer.auth.service.TenantContext;
import com.coffer.file.domain.CategoryType;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.governance.application.ArchiveOperationPersistenceService;
import com.coffer.governance.application.ArchiveRollbackPersistenceService;
import com.coffer.governance.application.ArchiveSnapshotService;
import com.coffer.governance.application.GovernanceCompensationProcessor;
import com.coffer.governance.domain.*;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationBatchRepository;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationItemRepository;
import com.coffer.governance.infrastructure.persistence.GovernanceCompensationTaskRepository;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Separate-JVM durable-boundary probe driven by verify-file-crash-recovery.ps1. */
public final class GovernanceCrashProbeMain {
    private static final String USERNAME = "governance-crash-probe-owner";
    private static final String PASSWORD = "R32-probe-pass";
    private static final String BATCH_ID = "archive-crash-probe";
    private static final String TASK_ID = "governance-crash-file";
    private static final byte[] BODY = "governance-crash-safe-original".getBytes(StandardCharsets.UTF_8);
    private static final Set<String> STEPS = Set.of(
            "archive-ledger", "archive-source-verified", "archive-copy-unrecorded",
            "archive-target-recorded", "archive-metadata", "archive-source-removed",
            "rollback-ledger", "rollback-copy-unrecorded", "rollback-copy-verified",
            "rollback-metadata", "rollback-target-removed");

    private GovernanceCrashProbeMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !Set.of("crash", "verify").contains(args[0]) || !STEPS.contains(args[1]))
            throw new IllegalArgumentException("usage: crash|verify archive-*|rollback-*");
        try (var context = new SpringApplicationBuilder(CofferApplication.class)
                .profiles("desktop").properties("spring.main.banner-mode=off")
                .run("--server.port=0", "--spring.profiles.active=desktop",
                        "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=1",
                        "--spring.data.redis.connect-timeout=100ms", "--spring.data.redis.timeout=100ms",
                        "--minio.endpoint=http://127.0.0.1:1", "--coffer.import.inbox.enabled=false", "--coffer.embedding.enabled=false",
                        "--coffer.desktop.initialize=" + "crash".equals(args[0]))) {
            AppUserRepository users = context.getBean(AppUserRepository.class);
            Long owner = "crash".equals(args[0])
                    ? users.saveAndFlush(new AppUser(USERNAME, context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class).encode(PASSWORD), AuthRole.USER)).getId()
                    : users.findByUsername(USERNAME).orElseThrow().getId();
            TenantContext.set(owner);
            try {
                context.getBean(com.coffer.desktop.DesktopLibraryLayout.class).ensureOwnerWorkspace();
                String source = "users/" + owner + "/managed/files/governance-original.txt";
                String target = "users/" + owner + "/managed/archive/contracts/governance-archived.txt";
                if ("crash".equals(args[0])) {
                    crashAt(context, args[1], source, target);
                    throw new AssertionError("Runtime.halt unexpectedly returned");
                }
                verifyAfterRestart(context, args[1], source, target);
                verifyAuthenticatedHttpRead(context);
                System.out.println("CRASH_PROBE_RECOVERED=" + args[1]);
                System.out.flush();
            } finally { TenantContext.clear(); }
        }
    }

    private static void crashAt(ApplicationContext context, String step, String source, String target) {
        FileStoragePort storage = context.getBean(FileStoragePort.class);
        FileMetadataRepository files = context.getBean(FileMetadataRepository.class);
        ArchiveSnapshotService snapshots = context.getBean(ArchiveSnapshotService.class);
        ArchiveOperationBatchRepository batches = context.getBean(ArchiveOperationBatchRepository.class);
        ArchiveOperationItemRepository items = context.getBean(ArchiveOperationItemRepository.class);
        ArchiveOperationPersistenceService archive = context.getBean(ArchiveOperationPersistenceService.class);
        ArchiveRollbackPersistenceService rollback = context.getBean(ArchiveRollbackPersistenceService.class);

        var original = storage.write(source, new ByteArrayInputStream(BODY), "text/plain", BODY.length);
        FileMetadata file = files.saveAndFlush(FileMetadata.builder()
                .fileName("governance-original.txt").fileType("txt").fileSize((long) BODY.length)
                .storagePath(source).contentSha256(original.sha256()).contentEtag(original.etag())
                .taskId(TASK_ID).status(FileStatus.COMPLETED).category(CategoryType.REPORT)
                .summary("before").archived(false).revision(0L).build());
        ArchiveFormalSnapshot before = snapshots.capture(file, original);
        ArchiveFormalSnapshot after = snapshots.intendedTarget(before, "governance-archived.txt",
                target, CategoryType.CONTRACT.name(), "after", List.of());
        batches.saveAndFlush(ArchiveOperationBatch.builder().batchId(BATCH_ID)
                .source(ArchiveOperationSource.PREVIEW_CONFIRMATION).runMode(GovernanceRunMode.LOCAL)
                .requestId("governance-crash-request").totalCount(1).build());
        ArchiveOperationItem item = items.saveAndFlush(ArchiveOperationItem.builder()
                .batchId(BATCH_ID).fileId(file.getId()).itemKey(BATCH_ID + ":" + file.getId())
                .expectedRevision(0L).sourceFileName(before.fileName()).targetFileName(after.fileName())
                .sourceCategory(before.category()).targetCategory(after.category())
                .sourcePath(source).targetPath(target).sourceEtag(original.etag())
                .sourceSize(original.size()).sourceSha256(original.sha256())
                .snapshotVersion(ArchiveSnapshotService.VERSION)
                .sourceSnapshotJson(snapshots.encode(before)).targetSnapshotJson(snapshots.encode(after))
                .build());
        if ("archive-ledger".equals(step)) halt(step);

        archive.claimItem(item.getId());
        archive.markSourceVerified(item.getId());
        if ("archive-source-verified".equals(step)) halt(step);
        var archived = storage.copy(source, target, original.sha256());
        if ("archive-copy-unrecorded".equals(step)) halt(step);
        archive.markTargetCopied(item.getId(), archived.etag(), archived.size(), archived.sha256());
        if ("archive-target-recorded".equals(step)) halt(step);
        archive.applyFormalState(item.getId(), archived.etag(), archived.size(),
                archived.sha256(), "after", List.of());
        if ("archive-metadata".equals(step)) halt(step);
        storage.delete(source, original.sha256());
        if ("archive-source-removed".equals(step)) halt(step);
        archive.markSucceeded(item.getId());
        archive.recomputeBatch(BATCH_ID);

        if (!rollback.prepareBatch(BATCH_ID)) throw new AssertionError("rollback was not prepared");
        if ("rollback-ledger".equals(step)) halt(step);
        rollback.claim(item.getId());
        rollback.markCopying(item.getId());
        var restored = storage.copy(target, source, archived.sha256());
        if ("rollback-copy-unrecorded".equals(step)) halt(step);
        if (!restored.sha256().equalsIgnoreCase(before.sha256()))
            throw new AssertionError("rollback copy changed the bytes");
        rollback.markDbCommitting(item.getId());
        if ("rollback-copy-verified".equals(step)) halt(step);
        rollback.restoreMetadata(item.getId(), restored.etag(), restored.sha256());
        if ("rollback-metadata".equals(step)) halt(step);
        storage.delete(target, archived.sha256());
        if ("rollback-target-removed".equals(step)) halt(step);
        throw new AssertionError("unknown crash step");
    }

    private static void verifyAfterRestart(ApplicationContext context, String step,
                                           String source, String target) throws Exception {
        var tasks = context.getBean(GovernanceCompensationTaskRepository.class);
        var processor = context.getBean(GovernanceCompensationProcessor.class);
        for (int round = 0; round < 6; round++) {
            List<Long> pending = tasks.findAll().stream()
                    .filter(t -> t.getStatus() == GovernanceCompensationStatus.PENDING)
                    .map(GovernanceCompensationTask::getId).toList();
            if (pending.isEmpty()) break;
            for (Long id : pending) processor.process(id);
        }
        FileStoragePort storage = context.getBean(FileStoragePort.class);
        FileMetadata file = context.getBean(FileMetadataRepository.class)
                .findByTaskId(TASK_ID).orElseThrow();
        ArchiveOperationItem item = context.getBean(ArchiveOperationItemRepository.class)
                .findByBatchIdOrderByIdAsc(BATCH_ID).get(0);
        ArchiveOperationBatch batch = context.getBean(ArchiveOperationBatchRepository.class)
                .findByBatchId(BATCH_ID).orElseThrow();
        ArchiveSnapshotService snapshots = context.getBean(ArchiveSnapshotService.class);
        ArchiveFormalSnapshot before = snapshots.requireSource(item);
        ArchiveFormalSnapshot after = snapshots.requireTarget(item);

        if ("archive-copy-unrecorded".equals(step)) {
            if (item.getExecutionStatus() != ArchiveOperationItemExecutionStatus.CONFLICTED
                    || batch.getStatus() != ArchiveOperationBatchStatus.FAILED
                    || !snapshots.matches(file, before) || !storage.exists(source) || !storage.exists(target))
                throw new AssertionError("ambiguous archive copy was not quarantined");
        } else if ("rollback-copy-unrecorded".equals(step)) {
            if (item.getRollbackStatus() != ArchiveOperationItemRollbackStatus.CONFLICTED
                    || batch.getRollbackStatus() != ArchiveOperationRollbackStatus.FAILED
                    || !snapshots.matches(file, after) || !storage.exists(source) || !storage.exists(target))
                throw new AssertionError("ambiguous rollback copy was not quarantined");
        } else if (step.startsWith("archive-")) {
            if (item.getExecutionStatus() != ArchiveOperationItemExecutionStatus.SUCCEEDED
                    || batch.getStatus() != ArchiveOperationBatchStatus.SUCCEEDED
                    || !snapshots.matches(file, after) || storage.exists(source) || !storage.exists(target))
                throw new AssertionError("archive did not recover complete formal state");
        } else {
            if (item.getRollbackStatus() != ArchiveOperationItemRollbackStatus.SUCCEEDED
                    || batch.getRollbackStatus() != ArchiveOperationRollbackStatus.SUCCEEDED
                    || !snapshots.matchesAfterRollback(file, before, item.getRollbackResultRevision())
                    || !storage.exists(source) || storage.exists(target))
                throw new AssertionError("rollback did not recover complete original state");
        }
        String finalPath = step.startsWith("rollback-") && !"rollback-copy-unrecorded".equals(step)
                ? source : ("archive-copy-unrecorded".equals(step) ? source : target);
        try (var input = storage.read(finalPath)) {
            if (!Arrays.equals(BODY, input.readAllBytes())) throw new AssertionError("governance recovery changed original bytes");
        }
        if ("archive-copy-unrecorded".equals(step) || "rollback-copy-unrecorded".equals(step)) {
            if (tasks.findAll().stream().anyMatch(task -> task.getStatus() == GovernanceCompensationStatus.SUCCEEDED))
                throw new AssertionError("ambiguous recovery was reported as successful compensation");
            if (tasks.findAll().stream().noneMatch(task -> task.getStatus() == GovernanceCompensationStatus.MANUAL_REVIEW))
                throw new AssertionError("ambiguous recovery has no durable manual-review task");
        }
    }

    private static void verifyAuthenticatedHttpRead(ApplicationContext context) throws Exception {
        int port = ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext) context).getWebServer().getPort();
        String origin = "http://127.0.0.1:" + port;
        var cookies = new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL);
        var client = java.net.http.HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(java.time.Duration.ofSeconds(3)).build();
        client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin + "/api/auth/csrf")).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.discarding());
        String csrf = cookies.getCookieStore().getCookies().stream().filter(cookie -> "XSRF-TOKEN".equals(cookie.getName())).findFirst().orElseThrow().getValue();
        String body = context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).writeValueAsString(java.util.Map.of("username", USERNAME, "password", PASSWORD));
        var login = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin + "/api/auth/login"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(), java.net.http.HttpResponse.BodyHandlers.discarding());
        if (login.statusCode() != 200) throw new AssertionError("offline login failed after restart");
        var file = context.getBean(FileMetadataRepository.class).findByTaskId(TASK_ID).orElseThrow();
        java.net.URI url = java.net.URI.create(origin + "/api/files/" + file.getId() + "/content?revision=" + file.getRevision());
        var response = client.send(java.net.http.HttpRequest.newBuilder(url).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || !Arrays.equals(response.body(), BODY))
            throw new AssertionError("authenticated offline file read failed after restart");
        int anonymous = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(url).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
        if (anonymous != 401) throw new AssertionError("offline file read bypassed authentication");
    }

    private static void halt(String step) {
        System.out.println("CRASH_PROBE_HALTING=" + step);
        System.out.flush();
        Runtime.getRuntime().halt(73);
    }
}
