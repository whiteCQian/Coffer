package com.coffer.file.application;

import com.coffer.file.domain.FileMetadata;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.file.storage.StorageConflictException;
import com.coffer.auth.service.TenantContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Opens the exact stored file identity and checks all bytes before parsed content may be used. */
public final class VerifiedFileSource {
    private static final Semaphore OPEN_SLOTS = new Semaphore(2);
    private static final Semaphore READ_SLOTS = new Semaphore(2);
    private static final long OPEN_TIMEOUT_MILLIS = 20_000;
    private VerifiedFileSource() { }

    public static InputStream open(FileStoragePort storage, FileMetadata file) {
        return open(storage, file, OPEN_TIMEOUT_MILLIS);
    }

    static InputStream open(FileStoragePort storage, FileMetadata file, long timeoutMillis) {
        if (storage == null || file == null || file.getStoragePath() == null
                || file.getContentSha256() == null || !file.getContentSha256().matches("[0-9a-f]{64}")
                || file.getFileSize() == null || file.getFileSize() < 0)
            throw new StorageConflictException("文件缺少可验证的正文身份");
        if (timeoutMillis <= 0) throw new SourceTimeoutException();
        String key = file.getStoragePath();
        String sha256 = file.getContentSha256();
        long size = file.getFileSize();
        Long owner = TenantContext.currentTenantId();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !OPEN_SLOTS.tryAcquire(remaining, TimeUnit.NANOSECONDS))
                throw new SourceTimeoutException();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("文件来源读取已中断", interrupted);
        }

        CompletableFuture<InputStream> result = new CompletableFuture<>();
        AtomicBoolean abandoned = new AtomicBoolean();
        AtomicBoolean closeStarted = new AtomicBoolean();
        AtomicReference<InputStream> opened = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                TenantContext.runAs(owner, () -> {
                    var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(authentication);
                    SecurityContextHolder.setContext(context);
                    try {
                        InputStream source = openDirect(storage, key, size, sha256);
                        opened.set(source);
                        if (abandoned.get()) closeAbandoned(source, closeStarted);
                        else result.complete(source);
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            } finally {
                OPEN_SLOTS.release();
            }
        }, "coffer-verified-source-open");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException | Error failure) {
            OPEN_SLOTS.release();
            throw failure;
        }
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            return result.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            abandon(worker, abandoned, opened, closeStarted);
            throw new SourceTimeoutException();
        } catch (InterruptedException interrupted) {
            abandon(worker, abandoned, opened, closeStarted);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("文件来源读取已中断", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("文件来源读取失败", cause);
        }
    }

    private static InputStream openDirect(FileStoragePort storage, String key, long size, String sha256) {
        var observed = storage.stat(key);
        if (!Objects.equals(sha256, observed.sha256()) || size != observed.size())
            throw new StorageConflictException("文件正文与元数据指纹不一致");
        return new CheckedInput(storage.readIfUnchanged(key, observed), observed.size(), observed.sha256());
    }

    /** Bounded identity check for parse persistence and model authorization after reading. */
    public static FileStoragePort.StoredObject statBounded(FileStoragePort storage, String key) {
        return statBounded(storage, key, OPEN_TIMEOUT_MILLIS);
    }

    static FileStoragePort.StoredObject statBounded(FileStoragePort storage, String key, long timeoutMillis) {
        if (storage == null || key == null || key.isBlank())
            throw new StorageConflictException("文件缺少可验证的正文路径");
        if (timeoutMillis <= 0) throw new SourceTimeoutException();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !OPEN_SLOTS.tryAcquire(remaining, TimeUnit.NANOSECONDS))
                throw new SourceTimeoutException();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("文件来源检查已中断", interrupted);
        }
        Long owner = TenantContext.currentTenantId();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        FutureTask<FileStoragePort.StoredObject> result = new FutureTask<>(() -> {
            try {
                return TenantContext.callAs(owner, () -> {
                    var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(authentication);
                    SecurityContextHolder.setContext(context);
                    try { return storage.stat(key); }
                    finally { SecurityContextHolder.clearContext(); }
                });
            } finally {
                OPEN_SLOTS.release();
            }
        });
        Thread worker = new Thread(result, "coffer-verified-source-stat");
        worker.setDaemon(true);
        try { worker.start(); }
        catch (RuntimeException | Error failure) {
            OPEN_SLOTS.release();
            throw failure;
        }
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            return result.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            worker.interrupt();
            throw new SourceTimeoutException();
        } catch (InterruptedException interrupted) {
            worker.interrupt();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("文件来源检查已中断", interrupted);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("文件来源检查失败", cause);
        }
    }

    /** Used for image model input, which must be materialized before the parser can inspect it. */
    public static byte[] readBounded(FileStoragePort storage, FileMetadata file, int maxBytes) {
        return readBounded(storage, file, maxBytes, OPEN_TIMEOUT_MILLIS);
    }

    static byte[] readBounded(FileStoragePort storage, FileMetadata file, int maxBytes, long timeoutMillis) {
        if (maxBytes < 0 || maxBytes == Integer.MAX_VALUE)
            throw new IllegalArgumentException("无效的来源读取上限");
        if (timeoutMillis <= 0) throw new SourceTimeoutException();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        InputStream source = open(storage, file, timeoutMillis);
        AtomicBoolean closeStarted = new AtomicBoolean();
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !READ_SLOTS.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                closeAbandoned(source, closeStarted);
                throw new SourceTimeoutException();
            }
        } catch (InterruptedException interrupted) {
            closeAbandoned(source, closeStarted);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("文件来源读取已中断", interrupted);
        }
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try (source) {
                result.complete(source.readNBytes(maxBytes + 1));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            } finally {
                READ_SLOTS.release();
            }
        }, "coffer-verified-source-read");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException | Error failure) {
            READ_SLOTS.release();
            closeAbandoned(source, closeStarted);
            throw failure;
        }
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            return result.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            worker.interrupt();
            closeAbandoned(source, closeStarted);
            throw new SourceTimeoutException();
        } catch (InterruptedException interrupted) {
            worker.interrupt();
            closeAbandoned(source, closeStarted);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("文件来源读取已中断", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("文件来源读取失败", cause);
        }
    }

    private static void abandon(Thread worker, AtomicBoolean abandoned,
                                AtomicReference<InputStream> opened, AtomicBoolean closeStarted) {
        abandoned.set(true);
        worker.interrupt();
        closeAbandoned(opened.get(), closeStarted);
    }

    private static void closeAbandoned(InputStream source, AtomicBoolean closeStarted) {
        if (source == null || !closeStarted.compareAndSet(false, true)) return;
        Thread closer = new Thread(() -> {
            try { source.close(); }
            catch (IOException ignored) { }
        }, "coffer-verified-source-close");
        closer.setDaemon(true);
        closer.start();
    }

    public static final class SourceTimeoutException extends IllegalStateException {
        public SourceTimeoutException() { super("文件来源读取超过 20 秒上限"); }
    }

    private static final class CheckedInput extends FilterInputStream {
        private final long expectedSize;
        private final String expectedSha256;
        private final MessageDigest digest;
        private long count;
        private boolean verified;

        private CheckedInput(InputStream source, long expectedSize, String expectedSha256) {
            super(source);
            this.expectedSize = expectedSize;
            this.expectedSha256 = expectedSha256;
            try { this.digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }

        @Override public int read() throws IOException {
            int value = in.read();
            if (value == -1) verify();
            else {
                digest.update((byte) value);
                count++;
                checkLength();
            }
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) return 0;
            int read = in.read(bytes, offset, length);
            if (read == -1) verify();
            else if (read > 0) {
                digest.update(bytes, offset, read);
                count += read;
                checkLength();
            }
            return read;
        }

        @Override public long skip(long amount) throws IOException {
            if (amount <= 0) return 0;
            byte[] discard = new byte[8192];
            long skipped = 0;
            while (skipped < amount) {
                int read = read(discard, 0, (int) Math.min(discard.length, amount - skipped));
                if (read == -1) break;
                skipped += read;
            }
            return skipped;
        }

        @Override public boolean markSupported() { return false; }
        @Override public void mark(int limit) { }
        @Override public void reset() throws IOException { throw new IOException("已校验文件流不能重置"); }

        private void checkLength() {
            if (count > expectedSize) throw new StorageConflictException("读取的正文超过文件记录长度");
        }

        private void verify() {
            if (verified) return;
            if (count != expectedSize || !expectedSha256.equals(HexFormat.of().formatHex(digest.digest())))
                throw new StorageConflictException("读取的正文与文件记录指纹不一致");
            verified = true;
        }
    }
}
