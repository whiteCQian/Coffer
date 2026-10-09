package com.coffer.inbox;

import com.coffer.config.InboxImportProperties;
import com.coffer.file.api.dto.FileUploadResponse;
import com.coffer.file.application.FileUploadApplicationService;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.inbox.application.InboxImportScanner;
import com.coffer.inbox.domain.InboxImportRecord;
import com.coffer.inbox.domain.InboxImportStatus;
import com.coffer.inbox.infrastructure.persistence.InboxImportRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Unit coverage for stable-file detection, duplicate suppression and failures. */
@ExtendWith(MockitoExtension.class)
class InboxImportScannerTest {
    @org.junit.jupiter.api.AfterEach void clearOwner() { com.coffer.auth.service.TenantContext.clear(); }

    @TempDir
    Path inbox;

    @Mock
    private InboxImportRecordRepository recordRepository;

    @Mock
    private FileUploadApplicationService fileUploadApplicationService;

    @Mock
    private FileMetadataRepository fileMetadataRepository;

    private InboxImportProperties properties;
    private Map<String, InboxImportRecord> records;
    private InboxImportScanner scanner;

    @BeforeEach
    void setUp() throws IOException {
        com.coffer.auth.service.TenantContext.set(7L);
        Path template = inbox.resolve("{ownerId}");
        inbox = Files.createDirectories(inbox.resolve("7"));
        properties = new InboxImportProperties();
        properties.setEnabled(true);
        properties.setDirectory(template.toString());
        properties.setStableObservationThreshold(2);
        properties.setRetryDelayMs(60_000);
        records = new HashMap<>();

        when(recordRepository.findBySnapshotKey(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(records.get(invocation.getArgument(0, String.class))));
        when(recordRepository.findById(anyLong())).thenAnswer(invocation -> records.values().stream()
                .filter(record -> record.getId().equals(invocation.getArgument(0, Long.class)))
                .findFirst());
        when(recordRepository.findFirstByContentSha256AndStatusIn(anyString(), anyCollection()))
                .thenAnswer(invocation -> {
                    String hash = invocation.getArgument(0, String.class);
                    Collection<InboxImportStatus> statuses = invocation.getArgument(1, Collection.class);
                    return records.values().stream()
                            .filter(record -> hash.equals(record.getContentSha256()))
                            .filter(record -> statuses.contains(record.getStatus()))
                            .findFirst();
                });
        doAnswer(invocation -> {
            InboxImportRecord record = invocation.getArgument(0, InboxImportRecord.class);
            if (record.getId() == null) {
                record.setId((long) records.size() + 1);
            }
            records.put(record.getSnapshotKey(), record);
            return record;
        }).when(recordRepository).save(any(InboxImportRecord.class));
        when(recordRepository.claimForImport(anyLong(), anyLong(), any(InboxImportStatus.class), anyCollection(),
                org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenAnswer(invocation -> {
                    Long id = invocation.getArgument(0, Long.class);
                    InboxImportStatus target = invocation.getArgument(2, InboxImportStatus.class);
                    Collection<InboxImportStatus> claimable = invocation.getArgument(3, Collection.class);
                    Optional<InboxImportRecord> record = records.values().stream()
                            .filter(candidate -> candidate.getId().equals(id))
                            .findFirst();
                    int maxAttempts = invocation.getArgument(4, Integer.class);
                    if (record.isPresent() && claimable.contains(record.get().getStatus())
                            && record.get().getAttemptCount() < maxAttempts) {
                        record.get().setStatus(target);
                        record.get().setAttemptCount(record.get().getAttemptCount() + 1);
                        record.get().setImportStartedAt(invocation.getArgument(5));
                        return 1;
                    }
                    return 0;
                });

        scanner = new InboxImportScanner(properties, recordRepository,
                fileUploadApplicationService, fileMetadataRepository);
    }

    @Test
    void waitsForASecondUnchangedObservationBeforeImporting() throws IOException {
        Path file = write("memo.txt", "stable content");
        FileUploadResponse response = FileUploadResponse.builder().taskId("task-c04-001").build();
        when(fileUploadApplicationService.importInboxFile(any(Path.class), anyLong(), anyLong(), anyLong()))
                .thenReturn(response);
        when(fileMetadataRepository.findByTaskId("task-c04-001"))
                .thenReturn(Optional.of(FileMetadata.builder().id(101L).build()));

        assertThat(scanner.scanOnce().imported()).isZero();
        assertThat(scanner.scanOnce().imported()).isEqualTo(1);
        assertThat(scanner.scanOnce().imported()).isZero();

        verify(fileUploadApplicationService).importInboxFile(file, Files.size(file),
                Files.getLastModifiedTime(file).toMillis(), 1L);
        assertThat(records.values()).singleElement()
                .extracting(InboxImportRecord::getStatus)
                .isEqualTo(InboxImportStatus.IMPORTED);
    }

    @Test
    void treatsSameContentAtAnotherPathAsDuplicate() throws IOException {
        Path original = write("a.txt", "same content");
        when(fileUploadApplicationService.importInboxFile(any(Path.class), anyLong(), anyLong(), anyLong()))
                .thenReturn(FileUploadResponse.builder().taskId("task-c04-002").build());
        when(fileMetadataRepository.findByTaskId("task-c04-002"))
                .thenReturn(Optional.of(FileMetadata.builder().id(102L).build()));
        scanner.scanOnce();
        scanner.scanOnce();

        Path duplicate = write("b.txt", "same content");
        scanner.scanOnce();
        assertThat(scanner.scanOnce().duplicate()).isEqualTo(1);

        verify(fileUploadApplicationService).importInboxFile(original, Files.size(original),
                Files.getLastModifiedTime(original).toMillis(), 1L);
        verifyNoMoreInteractions(fileUploadApplicationService);
        assertThat(records.values()).anyMatch(record -> record.getSourceFileName().equals("b.txt")
                && record.getStatus() == InboxImportStatus.DUPLICATE);
    }

    @Test
    void changedFileStartsAFreshStabilityWindow() throws IOException {
        Path file = write("changing.txt", "a");
        when(fileUploadApplicationService.importInboxFile(any(Path.class), anyLong(), anyLong(), anyLong()))
                .thenReturn(FileUploadResponse.builder().taskId("task-c04-changing").build());
        when(fileMetadataRepository.findByTaskId("task-c04-changing"))
                .thenReturn(Optional.of(FileMetadata.builder().id(103L).build()));
        assertThat(scanner.scanOnce().imported()).isZero();
        Files.writeString(file, "a much larger copy", StandardCharsets.UTF_8);
        assertThat(scanner.scanOnce().imported()).isZero();
        assertThat(scanner.scanOnce().imported()).isEqualTo(1);
    }

    @Test void retainedOriginalsDoNotStarveFilesBeyondTheFirstScanPage() throws IOException {
        properties.setMaxFilesPerScan(1);
        write("a.txt", "alpha"); write("b.txt", "beta"); write("c.txt", "gamma");
        when(fileUploadApplicationService.importInboxFile(any(Path.class), anyLong(), anyLong(), anyLong()))
                .thenReturn(FileUploadResponse.builder().taskId("page-task").build());
        when(fileMetadataRepository.findByTaskId("page-task"))
                .thenReturn(Optional.of(FileMetadata.builder().id(104L).build()));
        for (int i=0; i<9; i++) scanner.scanOnce();
        assertThat(records.values()).hasSize(3).allMatch(r -> r.getStatus() == InboxImportStatus.IMPORTED);
        org.mockito.Mockito.verify(fileUploadApplicationService, org.mockito.Mockito.times(3))
                .importInboxFile(any(Path.class), anyLong(), anyLong(), anyLong());
        try (var retained = Files.list(inbox)) { assertThat(retained.filter(Files::isRegularFile).count()).isEqualTo(3); }
    }

    @Test
    void recordsFailureAndDoesNotRetryBeforeBackoff() throws IOException {
        write("broken.txt", "stable content");
        when(fileUploadApplicationService.importInboxFile(any(Path.class), anyLong(), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("MinIO unavailable"));

        scanner.scanOnce();
        assertThat(scanner.scanOnce().failed()).isEqualTo(1);
        assertThat(scanner.scanOnce().failed()).isEqualTo(1);
        assertThat(records.values()).singleElement()
                .extracting(InboxImportRecord::getStatus)
                .isEqualTo(InboxImportStatus.FAILED);
    }

    @Test
    void exhaustedImportRequiresExplicitRetry() throws IOException {
        Path file = write("broken.txt", "stable content");
        properties.setMaxAttempts(1);
        when(fileUploadApplicationService.importInboxFile(any(Path.class), anyLong(), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("storage unavailable"));

        scanner.scanOnce();
        scanner.scanOnce();
        InboxImportRecord record = records.values().iterator().next();
        assertThat(record.getStatus()).isEqualTo(InboxImportStatus.MANUAL_REVIEW);
        assertThat(record.getAttemptCount()).isEqualTo(1);
        scanner.scanOnce();
        verify(fileUploadApplicationService).importInboxFile(file, Files.size(file),
                Files.getLastModifiedTime(file).toMillis(), 1L);

        scanner.retry(record.getId());
        assertThat(record.getStatus()).isEqualTo(InboxImportStatus.STABLE);
        assertThat(record.getAttemptCount()).isZero();
    }

    @Test
    void symbolicLinkInInboxIsNeverImported() throws IOException {
        Path outside = Files.writeString(inbox.getParent().resolve("outside.txt"), "secret");
        try { Files.createSymbolicLink(inbox.resolve("linked.txt"), outside); }
        catch (UnsupportedOperationException | SecurityException | IOException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links unavailable");
        }
        Path normal = write("normal.txt", "ordinary");
        when(fileUploadApplicationService.importInboxFile(any(Path.class), anyLong(), anyLong(), anyLong()))
                .thenReturn(FileUploadResponse.builder().taskId("normal-task").build());
        when(fileMetadataRepository.findByTaskId("normal-task"))
                .thenReturn(Optional.of(FileMetadata.builder().id(104L).build()));
        scanner.scanOnce();
        scanner.scanOnce();
        assertThat(records.values()).singleElement()
                .extracting(InboxImportRecord::getSourceFileName).isEqualTo("normal.txt");
        verify(fileUploadApplicationService).importInboxFile(normal, Files.size(normal),
                Files.getLastModifiedTime(normal).toMillis(), 1L);
        verifyNoMoreInteractions(fileUploadApplicationService);
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(inbox.resolve(name), content, StandardCharsets.UTF_8);
    }
}
