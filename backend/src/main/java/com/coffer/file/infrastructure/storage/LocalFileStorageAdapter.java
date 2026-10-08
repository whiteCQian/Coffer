package com.coffer.file.infrastructure.storage;

import com.coffer.file.storage.FileStoragePort;
import com.coffer.file.storage.StorageConflictException;
import com.coffer.file.storage.StorageKey;
import com.coffer.file.storage.StorageObjectNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** Local desktop library. The configured root must already exist and contain no links. */
@Component
@Profile("desktop")
@lombok.extern.slf4j.Slf4j
public final class LocalFileStorageAdapter implements FileStoragePort {
    private final Path root;

    public LocalFileStorageAdapter(@Value("${coffer.storage.local.root:}") String configuredRoot) {
        if (configuredRoot == null || configuredRoot.isBlank()) {
            throw new IllegalStateException("本地文件库根目录未配置");
        }
        try {
            Path configured = Path.of(configuredRoot).toAbsolutePath().normalize();
            verifyExistingAncestors(configured);
            if (!Files.isDirectory(configured, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("本地文件库根目录不存在或不是目录");
            }
            if (!Files.isReadable(configured) || !Files.isWritable(configured)) {
                throw new IllegalStateException("本地文件库根目录不可读写");
            }
            root = configured.toRealPath();
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("本地文件库根目录不可用", error);
        }
    }

    @Override
    public StoredObject write(String key, InputStream source, String contentType, long size) {
        return writeChecked(key, source, size, null);
    }

    @Override
    public java.util.List<String> listOwnedKeys() {
        long owner = com.coffer.auth.service.TenantContext.requireOwnerId();
        Path userRoot = root.resolve("users").resolve(Long.toString(owner));
        if (!Files.exists(userRoot, LinkOption.NOFOLLOW_LINKS)) return java.util.List.of();
        verifyDirectoryChain(userRoot, false);
        try (var paths = Files.walk(userRoot)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !path.getFileName().toString().startsWith(".coffer-stage-"))
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .peek(StorageKey::requireOwned)
                    .toList();
        } catch (IOException error) { throw ioFailure(error); }
    }

    /** Incomplete unpublished staging files survive process death; retire them after retention. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${coffer.storage.stage-cleanup-delay-ms:3600000}")
    public void cleanupStaleStages() {
        java.time.Instant cutoff = java.time.Instant.now().minus(java.time.Duration.ofHours(24));
        try (var paths = Files.walk(root)) {
            paths.filter(path -> path.getFileName() != null
                            && path.getFileName().toString().startsWith(".coffer-stage-"))
                    .forEach(path -> {
                        try {
                            BasicFileAttributes attributes = Files.readAttributes(path,
                                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                            if (!attributes.isRegularFile() || !attributes.lastModifiedTime().toInstant().isBefore(cutoff))
                                return;
                            verifyDirectoryChain(path.getParent(), false);
                            Files.deleteIfExists(path);
                        } catch (IOException | RuntimeException failure) {
                            log.warn("过期暂存文件待下轮清理 type={}", failure.getClass().getSimpleName());
                        }
                    });
        } catch (IOException error) {
            log.warn("本地暂存文件扫描失败 type={}", error.getClass().getSimpleName());
        }
    }

    private StoredObject writeChecked(String key, InputStream source, long size, String expectedSha256) {
        StorageKey.requireOwned(key);
        Objects.requireNonNull(source, "source");
        if (size < 0) throw new IllegalArgumentException("文件长度不能为负");
        Path target = locate(key, true);
        requireAbsent(target);
        Path stage = null;
        try {
            stage = Files.createTempFile(target.getParent(), ".coffer-stage-", ".tmp");
            MessageDigest digest = newDigest();
            long written = 0;
            try (FileChannel channel = FileChannel.open(stage, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = source.read(buffer)) != -1) {
                    if (count == 0) continue;
                    if (written > size - count) throw new IllegalArgumentException("文件长度与声明值不一致");
                    ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, count);
                    while (bytes.hasRemaining()) channel.write(bytes);
                    digest.update(buffer, 0, count);
                    written += count;
                }
                if (written != size) throw new IllegalArgumentException("文件长度与声明值不一致");
                channel.force(true);
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            if (expectedSha256 != null && !sha256.equals(expectedSha256)) {
                throw new StorageConflictException("源文件内容已变化");
            }
            verifyDirectoryChain(target.getParent(), false);
            requireAbsent(target);
            // A same-volume hard link publishes the fully forced staging inode atomically and
            // fails if target exists. Files.move(ATOMIC_MOVE) may replace an existing target.
            Files.createLink(target, stage);
            return new StoredObject(key, written, sha256, sha256);
        } catch (FileAlreadyExistsException error) {
            throw new StorageConflictException("目标存储对象已存在");
        } catch (IOException error) {
            throw ioFailure(error);
        } finally {
            if (stage != null) {
                try { Files.deleteIfExists(stage); }
                catch (IOException ignored) { /* an orphan stage never changes the published object */ }
            }
        }
    }

    @Override
    public InputStream read(String key) {
        Path file = requireFile(key);
        try {
            return Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException error) {
            throw new StorageObjectNotFoundException();
        } catch (IOException error) {
            throw ioFailure(error);
        }
    }

    @Override
    public InputStream readRange(String key, long offset, long length) {
        StorageKey.requireOwned(key);
        if (offset < 0 || length < 0) throw new IllegalArgumentException("读取范围无效");
        Path file = requireFile(key);
        try {
            SeekableByteChannel channel = Files.newByteChannel(file,
                    Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
            long size = channel.size();
            if (offset > size) {
                channel.close();
                throw new IllegalArgumentException("读取范围超出文件长度");
            }
            channel.position(offset);
            return new RangedInputStream(channel, Math.min(length, size - offset));
        } catch (NoSuchFileException error) {
            throw new StorageObjectNotFoundException();
        } catch (IOException error) {
            throw ioFailure(error);
        }
    }

    @Override
    public InputStream readIfUnchanged(String key, StoredObject expected) {
        return openIfUnchanged(key, expected, 0, null);
    }

    @Override
    public InputStream readRangeIfUnchanged(String key, StoredObject expected, long offset, long length) {
        if (offset < 0 || length <= 0 || expected == null || offset > expected.size()
                || length > expected.size() - offset) throw new IllegalArgumentException("读取范围无效");
        return openIfUnchanged(key, expected, offset, length);
    }

    private InputStream openIfUnchanged(String key, StoredObject expected, long offset, Long length) {
        StorageKey.requireOwned(key);
        if (expected == null || !key.equals(expected.key()) || expected.size() < 0
                || expected.sha256() == null || !expected.sha256().matches("[0-9a-f]{64}")
                || !expected.sha256().equals(expected.etag()))
            throw new IllegalArgumentException("已校验对象身份无效");
        Path file = requireFile(key);
        SeekableByteChannel channel = null;
        boolean handedOff = false;
        try {
            channel = Files.newByteChannel(file,
                    Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
            if (channel.size() != expected.size()) throw new StorageConflictException("文件大小已变化");
            MessageDigest digest = newDigest();
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            long read = 0;
            int count;
            while ((count = channel.read(buffer)) != -1) {
                if (count == 0) continue;
                digest.update(buffer.array(), 0, count);
                read += count;
                buffer.clear();
            }
            if (read != expected.size()
                    || !HexFormat.of().formatHex(digest.digest()).equals(expected.sha256()))
                throw new StorageConflictException("文件正文在校验后已变化");
            channel.position(offset);
            InputStream stream = new RangedInputStream(channel, length == null ? read : length);
            handedOff = true;
            return stream;
        } catch (NoSuchFileException error) {
            throw new StorageObjectNotFoundException();
        } catch (IOException error) {
            throw ioFailure(error);
        } finally {
            if (!handedOff && channel != null) {
                try { channel.close(); } catch (IOException ignored) { }
            }
        }
    }

    @Override
    public StoredObject stat(String key) {
        Path file = requireFile(key);
        try {
            BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            MessageDigest digest = newDigest();
            long read = 0;
            try (InputStream stream = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                    read += count;
                }
            }
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || before.size() != read || after.size() != read
                    || !Objects.equals(before.fileKey(), after.fileKey())
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
                throw new StorageConflictException("源文件内容已变化");
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            return new StoredObject(key, read, sha256, sha256);
        } catch (NoSuchFileException error) {
            throw new StorageObjectNotFoundException();
        } catch (IOException error) {
            throw ioFailure(error);
        }
    }

    @Override
    public boolean exists(String key) {
        StorageKey.requireOwned(key);
        Path file;
        try { file = locate(key, false); }
        catch (StorageObjectNotFoundException missing) { return false; }
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            requireRegularFile(attributes);
            return true;
        } catch (NoSuchFileException missing) {
            return false;
        } catch (IOException error) {
            throw ioFailure(error);
        }
    }

    @Override
    public StoredObject copy(String source, String target, String expectedSourceSha256) {
        StorageKey.requireOwned(source);
        StorageKey.requireOwned(target);
        requireDigest(expectedSourceSha256);
        StoredObject existing = stat(source);
        requireMatching(expectedSourceSha256, existing.sha256());
        try (InputStream input = read(source)) {
            return writeChecked(target, input, existing.size(), expectedSourceSha256);
        } catch (IOException error) {
            throw ioFailure(error);
        }
    }

    @Override
    public StoredObject move(String source, String target, String expectedSourceSha256) {
        StorageKey.requireOwned(source);
        StorageKey.requireOwned(target);
        StoredObject copied = copy(source, target, expectedSourceSha256);
        try {
            delete(source, expectedSourceSha256);
            return copied;
        } catch (RuntimeException failure) {
            try { delete(target, copied.sha256()); }
            catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    @Override
    public void delete(String key, String expectedSha256) {
        StorageKey.requireOwned(key);
        requireDigest(expectedSha256);
        StoredObject current = stat(key);
        requireMatching(expectedSha256, current.sha256());
        Path file = requireFile(key);
        try {
            Files.delete(file);
        } catch (NoSuchFileException error) {
            throw new StorageObjectNotFoundException();
        } catch (IOException error) {
            throw ioFailure(error);
        }
    }

    private Path requireFile(String key) {
        StorageKey.requireOwned(key);
        Path file = locate(key, false);
        try {
            requireRegularFile(Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
            if (!file.toRealPath().startsWith(root)) throw new SecurityException("存储对象逃逸文件库");
            return file;
        } catch (NoSuchFileException error) {
            throw new StorageObjectNotFoundException();
        } catch (IOException error) {
            throw ioFailure(error);
        }
    }

    private Path locate(String key, boolean createParents) {
        StorageKey.requireOwned(key);
        String[] segments = key.split("/");
        Path directory = root;
        for (int index = 0; index < segments.length - 1; index++) {
            directory = directory.resolve(segments[index]);
            verifyDirectoryChain(directory, createParents);
        }
        return directory.resolve(segments[segments.length - 1]);
    }

    private void verifyDirectoryChain(Path directory, boolean create) {
        if (!directory.startsWith(root)) throw new SecurityException("存储路径逃逸文件库");
        Path current = root;
        requireDirectory(current);
        for (Path segment : root.relativize(directory)) {
            current = current.resolve(segment);
            if (create) {
                try { Files.createDirectory(current); }
                catch (FileAlreadyExistsException ignored) { /* verify below */ }
                catch (IOException error) { throw ioFailure(error); }
            }
            requireDirectory(current);
        }
    }

    private void requireDirectory(Path directory) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
                    || !directory.toRealPath().startsWith(root)) {
                throw new SecurityException("文件库包含链接或非法目录");
            }
        } catch (NoSuchFileException error) {
            throw new StorageObjectNotFoundException();
        } catch (IOException error) {
            throw ioFailure(error);
        }
    }

    private static void verifyExistingAncestors(Path path) throws IOException {
        Path current = path.getRoot();
        if (current == null) throw new IllegalStateException("本地文件库根目录必须是绝对路径");
        for (Path segment : path) {
            current = current.resolve(segment);
            BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IllegalStateException("本地文件库路径包含符号链接或连接点");
            }
        }
    }

    private static void requireRegularFile(BasicFileAttributes attributes) {
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new SecurityException("存储对象不是普通文件");
        }
    }

    private static void requireAbsent(Path path) {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            throw new StorageConflictException("目标存储对象已存在");
        } catch (NoSuchFileException ignored) { /* target available */ }
        catch (IOException error) { throw ioFailure(error); }
    }

    private static void requireDigest(String sha256) {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("SHA-256 指纹无效");
        }
    }

    private static void requireMatching(String expected, String actual) {
        if (!MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new StorageConflictException("源文件内容已变化");
        }
    }

    private static MessageDigest newDigest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 不可用", impossible); }
    }

    private static UncheckedIOException ioFailure(IOException cause) {
        return new UncheckedIOException("本地文件库操作失败", cause);
    }

    private static final class RangedInputStream extends InputStream {
        private final SeekableByteChannel channel;
        private long remaining;
        private RangedInputStream(SeekableByteChannel channel, long remaining) {
            this.channel = channel;
            this.remaining = remaining;
        }
        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) return 0;
            if (remaining == 0) return -1;
            int count = channel.read(ByteBuffer.wrap(bytes, offset, (int) Math.min(length, remaining)));
            if (count > 0) remaining -= count;
            return count;
        }
        @Override public void close() throws IOException { channel.close(); }
    }
}
