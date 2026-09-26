package com.coffer.auth;

import com.coffer.auth.domain.*;
import com.coffer.auth.infrastructure.*;
import com.coffer.auth.service.*;
import com.coffer.config.HibernateTenantConfig;
import com.coffer.entity.*;
import com.coffer.file.domain.*;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.repository.ModelCredentialRepository;
import com.coffer.task.domain.*;
import com.coffer.task.infrastructure.persistence.AsyncTaskRepository;
import com.coffer.task.application.TaskProgressApplicationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = OwnerAuthorizationIntegrationTest.Config.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:r12;DB_CLOSE_DELAY=-1",
        "spring.jpa.open-in-view=false", "spring.jpa.show-sql=false",
        "spring.session.jdbc.initialize-schema=never"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class OwnerAuthorizationIntegrationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableAspectJAutoProxy
    @EntityScan("com.coffer")
    @Import({HibernateTenantConfig.class, CurrentTenantResolver.class, OwnerAuthorization.class,
            OwnerAuthorizationAspect.class, TaskProgressApplicationService.class, TenantJobRunner.class,
            OwnedJobAspect.class, Worker.class, com.coffer.config.SecurityConfig.class,
            AppUserDetailsService.class, com.coffer.task.api.TaskController.class,
            com.coffer.task.application.TaskOverviewService.class,
            com.coffer.exception.GlobalExceptionHandler.class, AdminAuthorization.class,
            OperationsOverviewService.class, com.coffer.auth.api.OperationsOverviewController.class,
            com.coffer.governance.application.ArchiveOperationQueryService.class, AccountService.class,
            com.coffer.file.application.FileService.class, com.coffer.file.application.assembler.FileResponseAssembler.class,
            com.coffer.file.application.FileOperationService.class, com.coffer.task.application.TaskRegistrationService.class,
            com.coffer.file.application.StorageDeletionTaskService.class,
            com.coffer.task.application.UploadTaskRecovery.class})
    static class Config {
        @Bean(name = "taskExecutor") java.util.concurrent.Executor taskExecutor() { return Runnable::run; }
        @Bean com.coffer.service.MinioStorageService storage() { return org.mockito.Mockito.mock(com.coffer.service.MinioStorageService.class); }
        @Bean com.coffer.service.VectorCleanupService vectorCleanup() { return org.mockito.Mockito.mock(com.coffer.service.VectorCleanupService.class); }
        @Bean com.coffer.file.application.async.AsyncFileProcessor processor() { return org.mockito.Mockito.mock(com.coffer.file.application.async.AsyncFileProcessor.class); }
    }

    static class Worker {
        final AsyncTaskRepository tasks;
        Worker(AsyncTaskRepository tasks) { this.tasks = tasks; }
        @OwnedJob
        public void execute(Work work) {
            var task = tasks.findByTaskId(work.taskId()).orElseThrow(ResourceNotFoundException::new);
            task.setResult("executed");
            tasks.save(task);
        }
    }
    record Work(Long ownerId, String taskId) implements OwnedWork { }

    @Autowired AppUserRepository users;
    @Autowired FileMetadataRepository files;
    @Autowired AsyncTaskRepository tasks;
    @Autowired ModelCredentialRepository credentials;
    @Autowired TaskProgressApplicationService progress;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TenantJobRunner runner;
    @Autowired Worker worker;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired OperationsOverviewService operations;
    @Autowired AccountService accounts;
    @Autowired com.coffer.file.application.FileService fileService;
    @Autowired com.coffer.file.application.FileOperationService fileOperations;
    @Autowired com.coffer.file.infrastructure.persistence.StorageDeletionTaskRepository deletionTasks;
    @Autowired com.coffer.task.application.UploadTaskRecovery uploadRecovery;
    @Autowired com.coffer.file.application.async.AsyncFileProcessor processor;
    @Autowired org.springframework.session.SessionRepository sessions;
    @Autowired org.springframework.context.ApplicationContext applicationContext;
    @Autowired com.coffer.governance.infrastructure.persistence.GovernancePreviewBatchRepository previews;
    @Autowired com.coffer.governance.infrastructure.persistence.ArchiveOperationBatchRepository batches;
    @Autowired com.coffer.governance.infrastructure.persistence.ArchiveOperationItemRepository items;
    @Autowired com.coffer.governance.application.ArchiveOperationQueryService ledger;
    AppUser a, b, admin;

    @BeforeEach void setup() {
        TenantContext.clear(); SecurityContextHolder.clearContext();
        String suffix = UUID.randomUUID().toString();
        a = users.save(new AppUser("a-" + suffix, "unused", AuthRole.USER));
        b = users.save(new AppUser("b-" + suffix, "unused", AuthRole.USER));
        admin = users.save(new AppUser("admin-" + suffix, "unused", AuthRole.ADMIN));
    }
    @AfterEach void cleanup() { TenantContext.clear(); SecurityContextHolder.clearContext(); }

    FileMetadata file(AppUser user) throws Exception {
        return TenantContext.callAs(user.getId(), () -> files.saveAndFlush(FileMetadata.builder()
                .fileName("private-marker-" + user.getId()).fileSize(12L).fileType("txt")
                .storagePath("users/" + user.getId() + "/files/sample.txt").build()));
    }

    @Test void primaryKeyReferenceBatchAndDetachedWritesAreOwnerScoped() throws Exception {
        FileMetadata secret = file(a);
        TenantContext.runAs(b.getId(), () -> {
            assertThat(files.findById(secret.getId())).isEmpty();
            assertThat(files.existsById(secret.getId())).isFalse();
            assertThat(files.findAllById(List.of(secret.getId()))).isEmpty();
            assertThatThrownBy(() -> files.getReferenceById(secret.getId())).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> files.save(secret)).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> files.save(FileMetadata.builder().id(secret.getId())
                    .fileName("attack").fileSize(1L).build())).isInstanceOf(ResourceNotFoundException.class);
            files.deleteById(secret.getId());
            files.deleteAllByIdInBatch(List.of(secret.getId()));
        });
        TenantContext.runAs(a.getId(), () -> assertThat(files.findById(secret.getId()).orElseThrow().getFileName()).startsWith("private-marker"));
    }

    @Test void noContextAdminDisabledAndSpoofedAuthenticationCannotUseServices() {
        assertThatThrownBy(() -> progress.getTaskProgress("guess")).isInstanceOf(AccessDeniedException.class);
        TenantContext.runAs(admin.getId(), () -> assertThatThrownBy(() -> files.findAll()).isInstanceOf(AccessDeniedException.class));
        a.setEnabled(false); users.save(a);
        TenantContext.runAs(a.getId(), () -> assertThatThrownBy(() -> progress.getTaskProgress("guess")).isInstanceOf(AccessDeniedException.class));
        var principal = AuthPrincipal.from(admin);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        TenantContext.runAs(b.getId(), () -> assertThatThrownBy(() -> files.findAll()).isInstanceOf(AccessDeniedException.class));
    }

    @Test void taskGuessingHasSameErrorAsMissingAndJobsRestoreContext() {
        String id = UUID.randomUUID().toString();
        TenantContext.runAs(a.getId(), () -> tasks.saveAndFlush(AsyncTask.builder().taskId(id).fileName("private").build()));
        TenantContext.runAs(b.getId(), () -> {
            assertThatThrownBy(() -> progress.getTaskProgress(id)).isInstanceOf(ResourceNotFoundException.class).hasMessage("资源不存在");
            assertThatThrownBy(() -> progress.getTaskProgress("absent")).isInstanceOf(ResourceNotFoundException.class).hasMessage("资源不存在");
            assertThatThrownBy(() -> worker.execute(new Work(a.getId(), id))).isInstanceOf(ResourceNotFoundException.class);
        });
        assertThatThrownBy(() -> worker.execute(new Work(b.getId(), id))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(TenantContext.currentTenantId()).isZero();
        worker.execute(new Work(a.getId(), id));
        assertThat(TenantContext.currentTenantId()).isZero();
        TenantContext.runAs(a.getId(), () -> assertThat(progress.getTaskProgress(id).getResult()).isEqualTo("executed"));
    }

    @Test void sameProviderIsIndependentForTwoOwners() {
        ModelProvider provider = ModelProvider.values()[0];
        for (AppUser user : List.of(a, b)) TenantContext.runAs(user.getId(), () -> credentials.saveAndFlush(
                ModelCredential.builder().provider(provider).encryptedApiKey("secret-" + user.getId()).updatedAt(LocalDateTime.now()).build()));
        TenantContext.runAs(a.getId(), () -> {
            assertThat(credentials.findById(provider).orElseThrow().getEncryptedApiKey()).isEqualTo("secret-" + a.getId());
            credentials.deleteById(provider);
        });
        TenantContext.runAs(b.getId(), () -> assertThat(credentials.findById(provider).orElseThrow().getEncryptedApiKey()).isEqualTo("secret-" + b.getId()));
    }

    @Test void switchingOwnerInsideTransactionIsRejected() throws Exception {
        file(a);
        TenantContext.runAs(a.getId(), () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            files.findAll();
            TenantContext.runAs(b.getId(), () -> assertThatThrownBy(() -> files.findAll()).isInstanceOf(AccessDeniedException.class));
        }));
    }

    @Test void schedulerSkipsAdminAndDisabledAndContinuesAfterFailure() {
        a.setEnabled(false); users.save(a);
        Set<Long> seen = new HashSet<>();
        runner.runForEnabledOwners(id -> { seen.add(id); throw new IllegalStateException("test"); });
        assertThat(seen).contains(b.getId()).doesNotContain(a.getId(), admin.getId());
        assertThat(TenantContext.currentTenantId()).isZero();
    }

    jakarta.servlet.http.Cookie session(AppUser user) {
        var context = SecurityContextHolder.createEmptyContext();
        var principal = AuthPrincipal.from(user).forSession();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        var session = sessions.createSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);
        sessions.save(session);
        return new jakarta.servlet.http.Cookie("COFFER_SESSION", Base64.getEncoder().encodeToString(session.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test void httpMatrixUsesRealSecurityAndUniform404() throws Exception {
        String id = UUID.randomUUID().toString();
        TenantContext.runAs(a.getId(), () -> tasks.saveAndFlush(AsyncTask.builder().taskId(id).fileName("private-marker").build()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tasks/" + id))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tasks/" + id).cookie(session(a)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.fileName").value("private-marker"));
        String denied = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tasks/" + id).cookie(session(b)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound()).andReturn().getResponse().getContentAsString();
        String missing = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tasks/absent").cookie(session(b)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(denied).isEqualTo(missing).doesNotContain("private-marker", id);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tasks/" + id).cookie(session(admin)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        String aggregate = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/operations").cookie(session(admin)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(aggregate).contains("pendingTasks").doesNotContain("private-marker", "fileName", "ownerId", "result");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/operations").cookie(session(a)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    @Test void nativeOwnerArgumentCannotSelectAnotherAccount() {
        TenantContext.runAs(a.getId(), () -> assertThatThrownBy(() -> files.fullTextSearch("marker", b.getId()))
                .isInstanceOf(ResourceNotFoundException.class));
    }

    @Test void previewLedgerAndExportStayPrivate() throws Exception {
        var file = file(a);
        String previewId = UUID.randomUUID().toString(), batchId = UUID.randomUUID().toString();
        TenantContext.runAs(a.getId(), () -> {
            previews.saveAndFlush(com.coffer.governance.domain.GovernancePreviewBatch.builder()
                    .previewId(previewId).requestId(previewId)
                    .source(com.coffer.governance.domain.GovernancePreviewSource.values()[0])
                    .runMode(com.coffer.governance.domain.GovernanceRunMode.API).expiresAt(LocalDateTime.now().plusHours(1)).build());
            batches.saveAndFlush(com.coffer.governance.domain.ArchiveOperationBatch.builder()
                    .batchId(batchId).previewId(previewId).requestId(batchId)
                    .source(com.coffer.governance.domain.ArchiveOperationSource.values()[0])
                    .runMode(com.coffer.governance.domain.GovernanceRunMode.API).build());
            items.saveAndFlush(com.coffer.governance.domain.ArchiveOperationItem.builder()
                    .batchId(batchId).fileId(file.getId()).itemKey(batchId + ":1")
                    .sourceFileName("private-marker").targetFileName("private-marker")
                    .sourcePath(file.getStoragePath()).targetPath(file.getStoragePath()).build());
        });
        TenantContext.runAs(b.getId(), () -> {
            assertThat(previews.findByPreviewId(previewId)).isEmpty();
            assertThat(batches.findByBatchId(batchId)).isEmpty();
            assertThat(items.findByBatchIdOrderByIdAsc(batchId)).isEmpty();
            assertThat(new String(ledger.export(batchId, file.getId(), null, "json").content(), java.nio.charset.StandardCharsets.UTF_8))
                    .doesNotContain("private-marker");
        });
        TenantContext.runAs(admin.getId(), () -> assertThatThrownBy(() -> ledger.export(batchId, null, null, "csv"))
                .isInstanceOf(AccessDeniedException.class));
    }

    @Test void fileServiceReadRenameRetryDeleteRequireOwnerAndDeletePersistsIntent() throws Exception {
        var secret = file(a);
        TenantContext.runAs(b.getId(), () -> {
            assertThatThrownBy(() -> fileService.getFileDetail(secret.getId())).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> fileService.requireFileForContent(secret.getId())).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> fileOperations.renameFile(secret.getId(), "attack")).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> fileOperations.retryFile(secret.getId())).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> fileOperations.deleteFile(secret.getId())).isInstanceOf(ResourceNotFoundException.class);
        });
        TenantContext.runAs(a.getId(), () -> {
            fileOperations.renameFile(secret.getId(), "renamed.txt");
            assertThat(fileService.getFileDetail(secret.getId()).getFileName()).isEqualTo("renamed.txt");
            fileOperations.deleteFile(secret.getId());
            assertThat(files.findById(secret.getId())).isEmpty();
            assertThat(deletionTasks.findAll()).anySatisfy(task -> {
                assertThat(task.getOwnerId()).isEqualTo(a.getId());
                assertThat(task.getObjectPath()).isEqualTo(secret.getStoragePath());
            });
        });
        TenantContext.runAs(b.getId(), () -> assertThat(deletionTasks.findAll()).isEmpty());
    }

    @Test void accountServiceRejectsForgedAdminAndDisableRevokesJdbcSession() throws Exception {
        var ordinary = AuthPrincipal.from(a);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ordinary, null, ordinary.getAuthorities()));
        assertThatThrownBy(() -> accounts.createUser(AuthPrincipal.from(admin), "attack", "password-long-enough"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(accounts::listUsers).isInstanceOf(AccessDeniedException.class);
        SecurityContextHolder.clearContext();
        var cookie = session(b);
        var principal = AuthPrincipal.from(admin);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        accounts.disableUser(principal, b.getId());
        SecurityContextHolder.clearContext();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tasks/anything").cookie(cookie))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        assertThat(users.findById(b.getId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test void everyPrivateRepositoryRejectsAnonymousAndAdminIncludingBulkMethods() {
        var repositories = applicationContext.getBeansOfType(OwnedRepository.class).values();
        assertThat(repositories.size()).isGreaterThanOrEqualTo(19);
        for (OwnedRepository<?, ?> repository : repositories) {
            assertThatThrownBy(repository::count).isInstanceOf(AccessDeniedException.class);
            TenantContext.runAs(admin.getId(), () -> {
                assertThatThrownBy(repository::count).isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(repository::findAll).isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(repository::deleteAllInBatch).isInstanceOf(AccessDeniedException.class);
            });
        }
    }

    @Test void restartUsesPersistedOwnerAndIntentWithoutRequestContext() throws Exception {
        String pendingA = "pending-" + UUID.randomUUID(), pendingB = "pending-" + UUID.randomUUID();
        String interrupted = "interrupted-" + UUID.randomUUID();
        for (var entry : Map.of(a, pendingA, b, pendingB).entrySet()) {
            var metadata = file(entry.getKey());
            TenantContext.runAs(entry.getKey().getId(), () -> {
                metadata.setTaskId(entry.getValue()); files.save(metadata);
                tasks.save(AsyncTask.builder().taskId(entry.getValue()).status(AsyncTaskStatus.PENDING).build());
            });
        }
        TenantContext.runAs(a.getId(), () -> tasks.save(AsyncTask.builder().taskId(interrupted).status(AsyncTaskStatus.PROCESSING).build()));
        b.setEnabled(false); users.save(b);
        Map<String, Long> dispatched = new HashMap<>();
        com.coffer.file.application.async.AsyncFileProcessor processorMock =
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(processor);
        org.mockito.Mockito.doAnswer(call -> { dispatched.put(call.getArgument(0), TenantContext.requireOwnerId()); return null; })
                .when(processorMock).processFileAsync(org.mockito.ArgumentMatchers.anyString());
        uploadRecovery.recover();
        assertThat(dispatched).containsEntry(pendingA, a.getId()).doesNotContainKey(pendingB);
        assertThat(TenantContext.currentTenantId()).isZero();
        TenantContext.runAs(a.getId(), () -> assertThat(tasks.findByTaskId(interrupted).orElseThrow().getStatus()).isEqualTo(AsyncTaskStatus.FAILED));
        b.setEnabled(true); users.save(b);
        uploadRecovery.recover();
        assertThat(dispatched).containsEntry(pendingB, b.getId());
        org.mockito.Mockito.reset(processorMock);
    }
}
