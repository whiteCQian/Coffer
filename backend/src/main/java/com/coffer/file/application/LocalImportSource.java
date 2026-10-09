package com.coffer.file.application;

import com.coffer.file.infrastructure.storage.SafeLocalPaths;
import com.coffer.file.application.parse.BoundedDocumentParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** Holds one checked read-only handle and a shared lock; importing never removes the source. */
public final class LocalImportSource implements AutoCloseable {
    private final Path path;
    private final BasicFileAttributes snapshot;
    private final FileChannel channel;
    private final FileLock lock;
    private LocalImportSource(Path path, BasicFileAttributes snapshot, FileChannel channel, FileLock lock) {
        this.path = path; this.snapshot = snapshot; this.channel = channel; this.lock = lock;
    }
    public static LocalImportSource open(Path path, long size, long modifiedMillis, String fileKey) throws IOException {
        var attributes = SafeLocalPaths.file(path);
        if (size < 0 || size > BoundedDocumentParser.MAX_BYTES) throw new IllegalArgumentException("文件超过 32MB 处理上限");
        if (attributes.size() != size || attributes.lastModifiedTime().toMillis() != modifiedMillis
                || (fileKey != null && !fileKey.equals(key(attributes))))
            throw new IllegalStateException("导入来源已变化，请重新扫描预览");
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        FileLock lock = null;
        try {
            lock = channel.tryLock(0, Long.MAX_VALUE, true);
            if (lock == null) throw new IllegalStateException("导入来源被占用，请关闭外部应用后重试");
            var source = new LocalImportSource(path, attributes, channel, lock);
            source.verify();
            return source;
        } catch (IOException | RuntimeException failed) {
            if (lock != null && lock.isValid()) lock.release();
            channel.close();
            throw failed;
        }
    }
    public String sha256() throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        var bytes = java.nio.ByteBuffer.allocate(64 * 1024);
        long length = 0;
        int read;
        channel.position(0);
        while ((read = channel.read(bytes)) != -1) {
            length += read;
            if (length > BoundedDocumentParser.MAX_BYTES) throw new IllegalArgumentException("文件超过 32MB 处理上限");
            digest.update(bytes.array(), 0, read); bytes.clear();
        }
        verify();
        if (length != snapshot.size()) throw new IllegalStateException("导入来源已变化，请重新扫描预览");
        channel.position(0);
        return HexFormat.of().formatHex(digest.digest());
    }
    public InputStream stream() throws IOException { channel.position(0); return Channels.newInputStream(channel); }
    public void verify() throws IOException {
        var current = SafeLocalPaths.file(path);
        if (current.size() != snapshot.size() || channel.size() != snapshot.size()
                || !Objects.equals(key(current), key(snapshot))
                || !current.lastModifiedTime().equals(snapshot.lastModifiedTime()))
            throw new IllegalStateException("导入来源已变化，请重新扫描预览");
    }
    public static String key(BasicFileAttributes attributes) {
        // Windows' default NIO provider may not expose fileKey. Keep its creation timestamp as a fallback discriminator.
        return attributes.fileKey() == null ? "created:" + attributes.creationTime() : attributes.fileKey().toString();
    }
    @Override public void close() throws IOException {
        try { if (lock.isValid()) lock.release(); } finally { channel.close(); }
    }
}
