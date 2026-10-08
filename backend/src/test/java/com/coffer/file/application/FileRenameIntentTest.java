package com.coffer.file.application;

import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileRenameIntentStatus;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.infrastructure.persistence.FileRenameIntentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:file_rename_intent_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.flyway.locations=classpath:db/migration/h2",
        "minio.access-key=test-access-key", "minio.secret-key=test-secret-key"})
class FileRenameIntentTest extends com.coffer.auth.OwnerTestSupport {
    @Autowired FileMetadataRepository files;
    @Autowired FileRenameIntentRepository intents;
    @Autowired FileRenameIntentService intentService;
    @Autowired FileOperationService operations;

    @Test void normalRenameCommitsIntentAndFileVersionTogether() {
        FileMetadata file = save("first.txt");
        operations.renameFile(file.getId(), "second.txt");
        FileMetadata current = files.findById(file.getId()).orElseThrow();
        assertThat(current.getFileName()).isEqualTo("second.txt");
        assertThat(current.getRevision()).isEqualTo(1L);
        assertThat(intents.findAll()).filteredOn(i -> i.getFileId().equals(file.getId()))
                .singleElement().extracting(i -> i.getStatus()).isEqualTo(FileRenameIntentStatus.APPLIED);
        operations.renameFile(file.getId(), "second.txt");
        assertThat(intents.findAll()).filteredOn(i -> i.getFileId().equals(file.getId())).hasSize(1);
    }

    @Test void committedIntentFromInterruptedRequestReplaysOnlyMatchingOriginalState() {
        FileMetadata file = save("before.txt");
        String id = intentService.prepare(file, "after.txt");
        assertThat(intents.findById(id).orElseThrow().getStatus()).isEqualTo(FileRenameIntentStatus.PREPARED);
        assertThat(intentService.dueIds(false)).doesNotContain(id);
        assertThat(intentService.dueIds(true)).contains(id);
        intentService.recoverOne(id);
        assertThat(files.findById(file.getId()).orElseThrow().getFileName()).isEqualTo("after.txt");
        assertThat(files.findById(file.getId()).orElseThrow().getRevision()).isEqualTo(1L);
        intentService.recoverOne(id);
        assertThat(intents.findById(id).orElseThrow().getStatus()).isEqualTo(FileRenameIntentStatus.APPLIED);
    }

    @Test void repeatedRecoveryFailureStopsUntilExplicitRetry() {
        FileMetadata file = save("retry.txt");
        String id = intentService.prepare(file, "renamed.txt");
        intentService.recordFailure(id, new IllegalStateException("temporary"));
        assertThat(intents.findById(id).orElseThrow().getStatus()).isEqualTo(FileRenameIntentStatus.FAILED);
        assertThat(intentService.dueIds(true)).doesNotContain(id);
        intentService.recordFailure(id, new IllegalStateException("temporary"));
        assertThat(intents.findById(id).orElseThrow().getStatus())
                .isEqualTo(FileRenameIntentStatus.MANUAL_REVIEW);
        assertThat(intents.findById(id).orElseThrow().getAttempts()).isEqualTo(2);
        assertThat(intentService.dueIds(false)).doesNotContain(id);
        intentService.retry(id);
        // The scheduled recovery may apply this intent before the assertion runs.
        intentService.recoverOne(id);
        assertThat(intents.findById(id).orElseThrow().getStatus()).isEqualTo(FileRenameIntentStatus.APPLIED);
        assertThat(files.findById(file.getId()).orElseThrow().getFileName()).isEqualTo("renamed.txt");
    }

    private FileMetadata save(String name) {
        return files.saveAndFlush(FileMetadata.builder().fileName(name).fileSize(4L).fileType("txt")
                .storagePath(ownerPath("files/" + name)).revision(0L).build());
    }
}
