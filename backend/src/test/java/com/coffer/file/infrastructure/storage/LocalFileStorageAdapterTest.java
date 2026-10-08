package com.coffer.file.infrastructure.storage;

import com.coffer.auth.service.TenantContext;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.file.storage.StorageConflictException;
import com.coffer.file.storage.StorageObjectNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.security.access.AccessDeniedException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileStorageAdapterTest {
    private static final String FIRST = "users/7/files/first.txt";
    private static final String SECOND = "users/7/archive/second.txt";

    @TempDir Path temporary;
    private LocalFileStorageAdapter storage;

    @BeforeEach void setUp() {
        TenantContext.set(7L);
        storage = new LocalFileStorageAdapter(temporary.toString());
    }
    @AfterEach void clearOwner() { TenantContext.clear(); }

    @Test void missingRootFailsClosedAtConstruction() {
        assertThatThrownBy(() -> new LocalFileStorageAdapter(temporary.resolve("missing").toString()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(temporary.resolve("missing"))).isFalse();
        assertThatThrownBy(() -> new LocalFileStorageAdapter(""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void writePublishesExactBytesAndNeverOverwrites() throws IOException {
        byte[] body = "私人正文 🗂".getBytes(StandardCharsets.UTF_8);
        FileStoragePort.StoredObject written = storage.write(FIRST, new ByteArrayInputStream(body), "text/plain", body.length);
        assertThat(written.key()).isEqualTo(FIRST);
        assertThat(written.size()).isEqualTo(body.length);
        assertThat(written.sha256()).isEqualTo(sha256(body));
        assertThat(written.etag()).isEqualTo(written.sha256());
        assertThat(storage.sha256(FIRST)).isEqualTo(written.sha256());
        assertThat(storage.stat(FIRST)).isEqualTo(written);
        assertThat(storage.read(FIRST).readAllBytes()).isEqualTo(body);
        assertThat(storage.exists(FIRST)).isTrue();
        assertThatThrownBy(() -> storage.write(FIRST, new ByteArrayInputStream("replace".getBytes(StandardCharsets.UTF_8)),
                "text/plain", 7)).isInstanceOf(StorageConflictException.class);
        assertThat(storage.read(FIRST).readAllBytes()).isEqualTo(body);
    }

    @Test void mismatchedLengthNeverPublishesPartialFile() {
        assertThatThrownBy(() -> storage.write(FIRST, new ByteArrayInputStream(new byte[] {1, 2}),
                "application/octet-stream", 3)).isInstanceOf(IllegalArgumentException.class);
        assertThat(storage.exists(FIRST)).isFalse();
        assertThatThrownBy(() -> storage.write(FIRST, new ByteArrayInputStream(new byte[] {1, 2}),
                "application/octet-stream", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(storage.exists(FIRST)).isFalse();
    }

    @Test void rangeIsBoundedAndOwned() throws IOException {
        storage.write(FIRST, new ByteArrayInputStream("0123456789".getBytes(StandardCharsets.UTF_8)), "text/plain", 10);
        try (var range = storage.readRange(FIRST, 3, 4)) {
            assertThat(new String(range.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("3456");
        }
        try (var range = storage.readRange(FIRST, 8, 100)) {
            assertThat(new String(range.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("89");
        }
        assertThatThrownBy(() -> storage.readRange(FIRST, 11, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.readRange(FIRST, -1, 1)).isInstanceOf(IllegalArgumentException.class);
        TenantContext.set(8L);
        assertThatThrownBy(() -> storage.read(FIRST)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> storage.exists(FIRST)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void verifiedReadRejectsAReplacedObjectBeforeReturningBytes() throws IOException {
        byte[] original = "before".getBytes(StandardCharsets.UTF_8);
        var observed = storage.write(FIRST, new ByteArrayInputStream(original), "text/plain", original.length);
        try (var full = storage.readIfUnchanged(FIRST, observed);
             var range = storage.readRangeIfUnchanged(FIRST, observed, 1, 3)) {
            assertThat(full.readAllBytes()).isEqualTo(original);
            assertThat(range.readAllBytes()).isEqualTo("efo".getBytes(StandardCharsets.UTF_8));
        }
        Files.writeString(temporary.resolve(FIRST), "after!", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> storage.readIfUnchanged(FIRST, observed))
                .isInstanceOf(StorageConflictException.class);
        assertThatThrownBy(() -> storage.readRangeIfUnchanged(FIRST, observed, 0, 2))
                .isInstanceOf(StorageConflictException.class);
    }

    @Test void copyMoveAndDeleteRequireExactDigestAndProtectDestinations() throws IOException {
        byte[] body = "version-one".getBytes(StandardCharsets.UTF_8);
        String hash = storage.write(FIRST, new ByteArrayInputStream(body), "text/plain", body.length).sha256();
        assertThatThrownBy(() -> storage.copy(FIRST, SECOND, "0".repeat(64)))
                .isInstanceOf(StorageConflictException.class);
        assertThat(storage.exists(SECOND)).isFalse();
        assertThat(storage.copy(FIRST, SECOND, hash).sha256()).isEqualTo(hash);
        assertThat(storage.exists(FIRST)).isTrue();
        assertThatThrownBy(() -> storage.move(FIRST, SECOND, hash)).isInstanceOf(StorageConflictException.class);
        assertThat(storage.exists(FIRST)).isTrue();
        assertThatThrownBy(() -> storage.delete(FIRST, "0".repeat(64)))
                .isInstanceOf(StorageConflictException.class);
        storage.delete(SECOND, hash);
        assertThat(storage.move(FIRST, SECOND, hash).sha256()).isEqualTo(hash);
        assertThat(storage.exists(FIRST)).isFalse();
        assertThatThrownBy(() -> storage.read(FIRST)).isInstanceOf(StorageObjectNotFoundException.class);
        storage.delete(SECOND, hash);
        assertThat(storage.exists(SECOND)).isFalse();
    }

    @Test void rejectsSymlinkParentAndObjectWhenHostSupportsLinks() throws IOException {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path owner = Files.createDirectories(temporary.resolve("users/7"));
        Path parentLink = owner.resolve("files");
        try {
            Files.createSymbolicLink(parentLink, outside);
        } catch (UnsupportedOperationException | SecurityException | IOException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks unavailable on this host");
            return;
        }
        assertThatThrownBy(() -> storage.write(FIRST, new ByteArrayInputStream(new byte[] {1}),
                "application/octet-stream", 1)).isInstanceOf(SecurityException.class);
        Files.delete(parentLink);
        Files.createDirectory(parentLink);
        Path outsideFile = Files.write(outside.resolve("private.txt"), new byte[] {9});
        Files.createSymbolicLink(parentLink.resolve("first.txt"), outsideFile);
        assertThatThrownBy(() -> storage.read(FIRST)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> storage.exists(FIRST)).isInstanceOf(SecurityException.class);
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void rejectsWindowsJunctionParentWithoutFollowingIt() throws Exception {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path owner = Files.createDirectories(temporary.resolve("users/7"));
        Path junction = owner.resolve("files");
        Process creation = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), outside.toString()).redirectErrorStream(true).start();
        try (var output = creation.getInputStream()) { output.readAllBytes(); }
        assertThat(creation.waitFor()).isZero();
        try {
            assertThatThrownBy(() -> storage.write(FIRST,
                    new ByteArrayInputStream(new byte[] {1}), "application/octet-stream", 1))
                    .isInstanceOf(SecurityException.class);
            assertThat(Files.exists(outside.resolve("first.txt"))).isFalse();
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    @Test void oldUnpublishedStagesAreRetiredWithoutTouchingFreshStagesOrPublishedFiles() throws IOException {
        Path directory = Files.createDirectories(temporary.resolve("users/7/files"));
        Path oldStage = Files.write(directory.resolve(".coffer-stage-old.tmp"), new byte[] {1});
        Path freshStage = Files.write(directory.resolve(".coffer-stage-fresh.tmp"), new byte[] {2});
        Files.setLastModifiedTime(oldStage, java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minus(java.time.Duration.ofHours(25))));
        byte[] body = "kept".getBytes(StandardCharsets.UTF_8);
        storage.write(FIRST, new ByteArrayInputStream(body), "text/plain", body.length);

        storage.cleanupStaleStages();

        assertThat(Files.exists(oldStage)).isFalse();
        assertThat(Files.exists(freshStage)).isTrue();
        assertThat(storage.read(FIRST).readAllBytes()).isEqualTo(body);
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }
}
