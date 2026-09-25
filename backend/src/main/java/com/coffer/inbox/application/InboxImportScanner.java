package com.coffer.inbox.application;

import com.coffer.config.InboxImportProperties;
import com.coffer.file.application.FileUploadApplicationService;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.api.dto.FileUploadResponse;
import com.coffer.inbox.api.dto.InboxImportItemResponse;
import com.coffer.inbox.api.dto.InboxImportProgressResponse;
import com.coffer.inbox.domain.InboxImportRecord;
import com.coffer.inbox.domain.InboxImportStatus;
import com.coffer.inbox.infrastructure.persistence.InboxImportRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Periodically discovers files in the configured inbox and registers stable
 * snapshots through the normal upload/analysis pipeline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboxImportScanner {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "txt", "pdf", "doc", "docx", "jpg", "jpeg", "png", "gif", "webp", "bmp");
    private static final List<InboxImportStatus> DUPLICATE_LOOKUP_STATUSES =
            List.of(InboxImportStatus.IMPORTED, InboxImportStatus.DUPLICATE);
    private static final List<InboxImportStatus> CLAIMABLE_STATUSES =
            List.of(InboxImportStatus.STABLE, InboxImportStatus.FAILED);

    private final InboxImportProperties properties;
    private final InboxImportRecordRepository recordRepository;
    private final FileUploadApplicationService fileUploadApplicationService;
    private final FileMetadataRepository fileMetadataRepository;

    private final ReentrantLock scanLock = new ReentrantLock();
    private final AtomicReference<LocalDateTime> lastScanAt = new AtomicReference<>();

    /** Scheduler entry point. The feature is a no-op until explicitly enabled. */
    @Scheduled(fixedDelayString = "${coffer.import.inbox.fixed-delay-ms:30000}")
    public void scanScheduled() {
        scanOnce();
    }

    /** Runs one scan, primarily exposed for deterministic tests and diagnostics. */
    public ScanSummary scanOnce() {
        if (!properties.isEnabled() || properties.getDirectory() == null
                || properties.getDirectory().isBlank()) {
            return ScanSummary.empty();
        }
        if (!scanLock.tryLock()) {
            log.debug("收件箱扫描仍在执行，跳过重入");
            return ScanSummary.empty();
        }

        LocalDateTime scanTime = LocalDateTime.now();
        lastScanAt.set(scanTime);
        int discovered = 0;
        int stable = 0;
        int imported = 0;
        int duplicate = 0;
        int failed = 0;
        int unsupported = 0;
        try {
            Path directory = Path.of(properties.getDirectory()).toAbsolutePath().normalize();
            if (!Files.isDirectory(directory)) {
                log.warn("收件箱目录不存在或不是目录，跳过扫描: {}", directory);
                return ScanSummary.empty();
            }

            List<Path> candidates;
            try (Stream<Path> paths = Files.list(directory)) {
                candidates = paths
                        .filter(Files::isRegularFile)
                        .sorted()
                        .limit(Math.max(1, properties.getMaxFilesPerScan()))
                        .toList();
            }

            for (Path candidate : candidates) {
                try {
                    ProcessResult result = processCandidate(candidate, scanTime);
                    discovered += result.discovered() ? 1 : 0;
                    stable += result.stable() ? 1 : 0;
                    imported += result.imported() ? 1 : 0;
                    duplicate += result.duplicate() ? 1 : 0;
                    failed += result.failed() ? 1 : 0;
                    unsupported += result.unsupported() ? 1 : 0;
                } catch (Exception e) {
                    // One unreadable/vanishing file must not prevent the rest of the batch.
                    log.warn("收件箱文件处理跳过 path={}, reason={}", candidate, e.getMessage());
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("收件箱扫描失败 directory={}, reason={}", properties.getDirectory(), e.getMessage());
        } finally {
            scanLock.unlock();
        }
        return new ScanSummary(discovered, stable, imported, duplicate, failed, unsupported);
    }

    /** Returns database-backed counts and recent rows for the UI progress panel. */
    public InboxImportProgressResponse getProgress() {
        int discovered = count(InboxImportStatus.DISCOVERED);
        int stable = count(InboxImportStatus.STABLE);
        int importing = count(InboxImportStatus.IMPORTING);
        int imported = count(InboxImportStatus.IMPORTED);
        int duplicate = count(InboxImportStatus.DUPLICATE);
        int failed = count(InboxImportStatus.FAILED);
        int unsupported = count(InboxImportStatus.UNSUPPORTED);
        int total = discovered + stable + importing + imported + duplicate + failed + unsupported;

        List<InboxImportItemResponse> items = recordRepository.findTop50ByOrderByUpdatedAtDesc().stream()
                .map(record -> InboxImportItemResponse.builder()
                        .fileName(record.getSourceFileName())
                        .status(record.getStatus())
                        .stableObservations(record.getStableObservations())
                        .attemptCount(record.getAttemptCount())
                        .taskId(record.getTaskId())
                        .error(record.getLastError())
                        .updatedAt(record.getUpdatedAt())
                        .build())
                .toList();

        return InboxImportProgressResponse.builder()
                .enabled(properties.isEnabled())
                .directory(properties.getDirectory())
                .lastScanAt(lastScanAt.get())
                .totalCount(total)
                .discoveredCount(discovered)
                .stableCount(stable)
                .importingCount(importing)
                .importedCount(imported)
                .duplicateCount(duplicate)
                .failedCount(failed)
                .unsupportedCount(unsupported)
                .items(items)
                .build();
    }

    private ProcessResult processCandidate(Path candidate, LocalDateTime now) throws IOException {
        FileSnapshot snapshot = captureSnapshot(candidate);
        String extension = extensionOf(snapshot.fileName());
        InboxImportRecord record = observeSnapshot(snapshot, now);

        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            if (record.getStatus() != InboxImportStatus.UNSUPPORTED) {
                record.setStatus(InboxImportStatus.UNSUPPORTED);
                record.setUpdatedAt(now);
                record.setImportFinishedAt(now);
                record.setLastError("不支持的文件类型: " + extension);
                recordRepository.save(record);
            }
            return ProcessResult.unsupportedOnly();
        }

        if (record.getStatus() == InboxImportStatus.DISCOVERED) {
            return ProcessResult.discoveredOnly();
        }
        if (record.getStatus() == InboxImportStatus.IMPORTED
                || record.getStatus() == InboxImportStatus.DUPLICATE) {
            return ProcessResult.terminal(record.getStatus());
        }
        if (record.getStatus() == InboxImportStatus.FAILED
                && record.getNextAttemptAt() != null
                && record.getNextAttemptAt().isAfter(now)) {
            return ProcessResult.failedOnly();
        }

        String contentSha256 = sha256(candidate);
        verifySnapshot(candidate, snapshot);
        record.setContentSha256(contentSha256);
        record.setUpdatedAt(now);
        recordRepository.save(record);

        Optional<InboxImportRecord> previousImport = recordRepository
                .findFirstByContentSha256AndStatusIn(contentSha256, DUPLICATE_LOOKUP_STATUSES);
        if (previousImport.isPresent() && !previousImport.get().getId().equals(record.getId())) {
            record.setStatus(InboxImportStatus.DUPLICATE);
            record.setImportFinishedAt(now);
            record.setNextAttemptAt(null);
            record.setLastError("内容已由其他收件箱快照导入");
            record.setUpdatedAt(now);
            recordRepository.save(record);
            return ProcessResult.duplicateOnly();
        }

        int claimed = recordRepository.claimForImport(
                record.getId(), InboxImportStatus.IMPORTING, CLAIMABLE_STATUSES, now);
        if (claimed != 1) {
            return ProcessResult.empty();
        }

        try {
            FileUploadResponse response = fileUploadApplicationService.importInboxFile(
                    candidate, snapshot.size(), snapshot.modifiedMillis());
            Long fileId = fileMetadataRepository.findByTaskId(response.getTaskId())
                    .map(FileMetadata::getId)
                    .orElse(null);
            record = recordRepository.findById(record.getId()).orElse(record);
            record.setStatus(InboxImportStatus.IMPORTED);
            record.setTaskId(response.getTaskId());
            record.setFileId(fileId);
            record.setImportFinishedAt(LocalDateTime.now());
            record.setNextAttemptAt(null);
            record.setLastError(null);
            record.setUpdatedAt(LocalDateTime.now());
            recordRepository.save(record);
            return ProcessResult.importedOnly();
        } catch (Exception e) {
            record = recordRepository.findById(record.getId()).orElse(record);
            record.setStatus(InboxImportStatus.FAILED);
            record.setImportFinishedAt(LocalDateTime.now());
            record.setNextAttemptAt(LocalDateTime.now().plusNanos(
                    Math.max(1L, properties.getRetryDelayMs()) * 1_000_000L));
            record.setLastError(messageOf(e));
            record.setUpdatedAt(LocalDateTime.now());
            recordRepository.save(record);
            log.warn("收件箱文件导入失败，将稍后重试 path={}, reason={}", candidate, e.getMessage());
            return ProcessResult.failedOnly();
        }
    }

    private InboxImportRecord observeSnapshot(FileSnapshot snapshot, LocalDateTime now) {
        String snapshotKey = snapshotKey(snapshot);
        Optional<InboxImportRecord> existing = recordRepository.findBySnapshotKey(snapshotKey);
        if (existing.isEmpty()) {
            int threshold = stableThreshold();
            InboxImportRecord created = InboxImportRecord.builder()
                    .snapshotKey(snapshotKey)
                    .sourcePath(snapshot.sourcePath())
                    .sourceFileName(snapshot.fileName())
                    .sourceSize(snapshot.size())
                    .sourceModifiedAt(toLocalDateTime(snapshot.modifiedMillis()))
                    .status(threshold <= 1 ? InboxImportStatus.STABLE : InboxImportStatus.DISCOVERED)
                    .stableObservations(1)
                    .attemptCount(0)
                    .firstSeenAt(now)
                    .lastSeenAt(now)
                    .stableSinceAt(threshold <= 1 ? now : null)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            return recordRepository.save(created);
        }

        InboxImportRecord record = existing.get();
        record.setLastSeenAt(now);
        record.setUpdatedAt(now);
        if (record.getStatus() == InboxImportStatus.IMPORTED
                || record.getStatus() == InboxImportStatus.DUPLICATE
                || record.getStatus() == InboxImportStatus.UNSUPPORTED) {
            recordRepository.save(record);
            return record;
        }
        if (record.getStatus() == InboxImportStatus.IMPORTING) {
            // A process restart may leave a claim behind; the durable row is safe to retry.
            record.setStatus(InboxImportStatus.STABLE);
        }
        int threshold = stableThreshold();
        int observations = Math.min(threshold, Math.max(1, record.getStableObservations()) + 1);
        record.setStableObservations(observations);
        if (observations >= threshold && (record.getStatus() != InboxImportStatus.FAILED
                || record.getNextAttemptAt() == null || !record.getNextAttemptAt().isAfter(now))) {
            record.setStatus(InboxImportStatus.STABLE);
            if (record.getStableSinceAt() == null) {
                record.setStableSinceAt(now);
            }
        }
        return recordRepository.save(record);
    }

    private FileSnapshot captureSnapshot(Path candidate) throws IOException {
        Path normalized = candidate.toAbsolutePath().normalize();
        return new FileSnapshot(
                normalized.toString(),
                normalized.getFileName().toString(),
                Files.size(normalized),
                Files.getLastModifiedTime(normalized).toMillis());
    }

    private void verifySnapshot(Path candidate, FileSnapshot expected) throws IOException {
        if (Files.size(candidate) != expected.size()
                || Files.getLastModifiedTime(candidate).toMillis() != expected.modifiedMillis()) {
            throw new IllegalStateException("文件在哈希期间发生变化: " + candidate);
        }
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream inputStream = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = inputStream.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 不支持 SHA-256", e);
        }
    }

    private String snapshotKey(FileSnapshot snapshot) {
        return sha256Text(snapshot.sourcePath() + "|" + snapshot.size() + "|" + snapshot.modifiedMillis());
    }

    private String sha256Text(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 不支持 SHA-256", e);
        }
    }

    private int stableThreshold() {
        return Math.max(1, properties.getStableObservationThreshold());
    }

    private int count(InboxImportStatus status) {
        return Math.toIntExact(recordRepository.countByStatus(status));
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1
                ? ""
                : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private LocalDateTime toLocalDateTime(long modifiedMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(modifiedMillis), ZoneId.systemDefault());
    }

    private String messageOf(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private record FileSnapshot(String sourcePath, String fileName, long size, long modifiedMillis) {
    }

    private record ProcessResult(boolean discovered, boolean stable, boolean imported,
                                 boolean duplicate, boolean failed, boolean unsupported) {

        static ProcessResult empty() {
            return new ProcessResult(false, false, false, false, false, false);
        }

        static ProcessResult discoveredOnly() {
            return new ProcessResult(true, false, false, false, false, false);
        }

        static ProcessResult importedOnly() {
            return new ProcessResult(false, true, true, false, false, false);
        }

        static ProcessResult duplicateOnly() {
            return new ProcessResult(false, true, false, true, false, false);
        }

        static ProcessResult failedOnly() {
            return new ProcessResult(false, true, false, false, true, false);
        }

        static ProcessResult unsupportedOnly() {
            return new ProcessResult(false, false, false, false, false, true);
        }

        static ProcessResult terminal(InboxImportStatus status) {
            // Terminal rows are intentionally not counted again on every later scan.
            return empty();
        }
    }

    /** Compact result for scheduler logs and deterministic unit tests. */
    public record ScanSummary(int discovered, int stable, int imported,
                              int duplicate, int failed, int unsupported) {

        static ScanSummary empty() {
            return new ScanSummary(0, 0, 0, 0, 0, 0);
        }
    }
}
