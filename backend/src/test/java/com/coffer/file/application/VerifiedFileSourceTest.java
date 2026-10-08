package com.coffer.file.application;

import com.coffer.file.domain.FileMetadata;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.file.storage.StorageConflictException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class VerifiedFileSourceTest {
    private static final String KEY = "users/7/files/source.txt";

    @org.junit.jupiter.api.AfterEach void clearOwner() {
        com.coffer.auth.service.TenantContext.clear();
    }

    @Test void readsOnlyTheObservedVersionAndVerifiesAllBytes() throws Exception {
        byte[] bytes = "source bytes".getBytes(StandardCharsets.UTF_8);
        FileStoragePort storage = mock(FileStoragePort.class);
        var observed = new FileStoragePort.StoredObject(KEY, bytes.length, sha(bytes), "etag-1");
        when(storage.stat(KEY)).thenReturn(observed);
        when(storage.readIfUnchanged(KEY, observed)).thenReturn(new ByteArrayInputStream(bytes));

        try (var input = VerifiedFileSource.open(storage, file(bytes))) {
            assertThat(input.readAllBytes()).isEqualTo(bytes);
        }
        verify(storage, never()).read(any());
    }

    @Test void staleMetadataNeverOpensTheBody() throws Exception {
        byte[] bytes = "source bytes".getBytes(StandardCharsets.UTF_8);
        FileStoragePort storage = mock(FileStoragePort.class);
        when(storage.stat(KEY)).thenReturn(new FileStoragePort.StoredObject(KEY, bytes.length,
                "0".repeat(64), "etag-2"));

        assertThatThrownBy(() -> VerifiedFileSource.open(storage, file(bytes)))
                .isInstanceOf(StorageConflictException.class);
        verify(storage, never()).readIfUnchanged(eq(KEY), any());
    }

    @Test void changedBytesOfTheSameLengthFailAtEndOfStream() throws Exception {
        byte[] bytes = "source bytes".getBytes(StandardCharsets.UTF_8);
        byte[] changed = "wrong! bytes".getBytes(StandardCharsets.UTF_8);
        FileStoragePort storage = mock(FileStoragePort.class);
        var observed = new FileStoragePort.StoredObject(KEY, bytes.length, sha(bytes), "etag-1");
        when(storage.stat(KEY)).thenReturn(observed);
        when(storage.readIfUnchanged(KEY, observed)).thenReturn(new ByteArrayInputStream(changed));

        try (var input = VerifiedFileSource.open(storage, file(bytes))) {
            assertThatThrownBy(input::readAllBytes).isInstanceOf(StorageConflictException.class);
        }
    }

    @Test void boundedOpenPropagatesOwnerContextToStorageWorker() throws Exception {
        byte[] bytes = "source bytes".getBytes(StandardCharsets.UTF_8);
        FileStoragePort storage = mock(FileStoragePort.class);
        var observed = new FileStoragePort.StoredObject(KEY, bytes.length, sha(bytes), "etag-1");
        com.coffer.auth.service.TenantContext.set(7L);
        when(storage.stat(KEY)).thenAnswer(invocation -> {
            assertThat(com.coffer.auth.service.TenantContext.requireOwnerId()).isEqualTo(7L);
            return observed;
        });
        when(storage.readIfUnchanged(KEY, observed)).thenAnswer(invocation -> {
            assertThat(com.coffer.auth.service.TenantContext.requireOwnerId()).isEqualTo(7L);
            return new ByteArrayInputStream(bytes);
        });

        try (var input = VerifiedFileSource.open(storage, file(bytes), 1000)) {
            assertThat(input.readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test void stalledStatTimesOutAndClosesAStreamOpenedTooLate() throws Exception {
        byte[] bytes = "source bytes".getBytes(StandardCharsets.UTF_8);
        FileStoragePort storage = mock(FileStoragePort.class);
        var observed = new FileStoragePort.StoredObject(KEY, bytes.length, sha(bytes), "etag-1");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        when(storage.stat(KEY)).thenAnswer(invocation -> {
            entered.countDown();
            for (;;) {
                try { release.await(); break; }
                catch (InterruptedException ignored) { }
            }
            return observed;
        });
        when(storage.readIfUnchanged(KEY, observed)).thenReturn(new InputStream() {
            private final ByteArrayInputStream delegate = new ByteArrayInputStream(bytes);
            @Override public int read() { return delegate.read(); }
            @Override public void close() throws IOException { closed.countDown(); delegate.close(); }
        });

        try {
            long started = System.nanoTime();
            assertThatThrownBy(() -> VerifiedFileSource.open(storage, file(bytes), 150))
                    .isInstanceOf(VerifiedFileSource.SourceTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
        assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test void boundedImageReadVerifiesBytesBeforeReturning() throws Exception {
        byte[] bytes = "image bytes".getBytes(StandardCharsets.UTF_8);
        FileStoragePort storage = mock(FileStoragePort.class);
        var observed = new FileStoragePort.StoredObject(KEY, bytes.length, sha(bytes), "etag-1");
        when(storage.stat(KEY)).thenReturn(observed);
        when(storage.readIfUnchanged(KEY, observed)).thenReturn(new ByteArrayInputStream(bytes));

        assertThat(VerifiedFileSource.readBounded(storage, file(bytes), bytes.length, 1000))
                .isEqualTo(bytes);
    }

    @Test void stalledImageReadTimesOutAndClosesTheSource() throws Exception {
        byte[] bytes = "image bytes".getBytes(StandardCharsets.UTF_8);
        FileStoragePort storage = mock(FileStoragePort.class);
        var observed = new FileStoragePort.StoredObject(KEY, bytes.length, sha(bytes), "etag-1");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        when(storage.stat(KEY)).thenReturn(observed);
        when(storage.readIfUnchanged(KEY, observed)).thenReturn(new InputStream() {
            @Override public int read() {
                entered.countDown();
                for (;;) {
                    try { release.await(); return -1; }
                    catch (InterruptedException ignored) { }
                }
            }
            @Override public void close() {
                closed.countDown();
                release.countDown();
            }
        });

        try {
            long started = System.nanoTime();
            assertThatThrownBy(() -> VerifiedFileSource.readBounded(storage, file(bytes), 100, 150))
                    .isInstanceOf(VerifiedFileSource.SourceTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(closed.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
    }

    @Test void laterIdentityCheckAlsoTimesOutWhenStorageStalls() throws Exception {
        FileStoragePort storage = mock(FileStoragePort.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        when(storage.stat(KEY)).thenAnswer(invocation -> {
            entered.countDown();
            for (;;) {
                try { release.await(); break; }
                catch (InterruptedException ignored) { }
            }
            exited.countDown();
            return null;
        });

        try {
            long started = System.nanoTime();
            assertThatThrownBy(() -> VerifiedFileSource.statBounded(storage, KEY, 150))
                    .isInstanceOf(VerifiedFileSource.SourceTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
        assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue();
    }

    private static FileMetadata file(byte[] bytes) throws Exception {
        return FileMetadata.builder().storagePath(KEY).fileSize((long) bytes.length)
                .contentSha256(sha(bytes)).build();
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
