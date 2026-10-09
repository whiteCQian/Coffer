package com.coffer.file.application;

import com.coffer.auth.service.TenantContext;
import com.coffer.file.infrastructure.storage.LocalFileStorageAdapter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class LocalImportSourceTest {
    @TempDir Path temp;
    @AfterEach void clear() { TenantContext.clear(); }
    @Test void changedSizeAndExclusiveLockFailBeforeCopying() throws Exception {
        Path source = Files.writeString(temp.resolve("source.txt"), "original");
        long modified = Files.getLastModifiedTime(source).toMillis();
        assertThatThrownBy(() -> LocalImportSource.open(source, 1, modified, null)).isInstanceOf(IllegalStateException.class);
        try (var held = FileChannel.open(source, StandardOpenOption.WRITE); var lock = held.lock()) {
            assertThatThrownBy(() -> LocalImportSource.open(source, Files.size(source), modified, null)).isInstanceOf(RuntimeException.class);
        }
        assertThat(Files.readString(source)).isEqualTo("original");
    }
    @Test void wrongPreviewDigestNeverPublishesALocalObject() throws Exception {
        Path source = Files.writeString(temp.resolve("source.txt"), "original");
        TenantContext.set(7L); var storage = new LocalFileStorageAdapter(temp.toString());
        try (var verified = LocalImportSource.open(source, Files.size(source), Files.getLastModifiedTime(source).toMillis(), null)) {
            assertThatThrownBy(() -> storage.writeVerified("users/7/managed/files/destination.txt", verified.stream(), "text/plain",
                    Files.size(source), "0".repeat(64))).isInstanceOf(com.coffer.file.storage.StorageConflictException.class);
        }
        assertThat(storage.exists("users/7/managed/files/destination.txt")).isFalse();
        assertThat(Files.readString(source)).isEqualTo("original");
    }
    @Test @EnabledIfEnvironmentVariable(named="COFFER_TEST_SECOND_VOLUME", matches=".+")
    void copiesFromAnotherVolumeWithoutDeletingTheSource() throws Exception {
        Path parent = Path.of(System.getenv("COFFER_TEST_SECOND_VOLUME")); Files.createDirectories(parent);
        Path source = parent.resolve("coffer-r31-" + UUID.randomUUID() + ".txt");
        try {
            Files.writeString(source, "跨盘原文件");
            assertThat(source.getRoot()).isNotEqualTo(temp.getRoot());
            TenantContext.set(7L); var storage = new LocalFileStorageAdapter(temp.toString());
            try (var verified = LocalImportSource.open(source, Files.size(source), Files.getLastModifiedTime(source).toMillis(), null)) {
                String sha = verified.sha256();
                var written = storage.writeVerified("users/7/managed/files/cross-volume.txt", verified.stream(), "text/plain", Files.size(source), sha);
                assertThat(written.sha256()).isEqualTo(sha); verified.verify();
            }
            assertThat(Files.readString(source)).isEqualTo("跨盘原文件");
        } finally { Files.deleteIfExists(source); }
    }
}
