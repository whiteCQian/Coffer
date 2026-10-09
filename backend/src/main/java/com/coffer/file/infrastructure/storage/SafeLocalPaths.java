package com.coffer.file.infrastructure.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** Common no-link checks for library, owner workspaces and externally produced inbox files. */
public final class SafeLocalPaths {
    private SafeLocalPaths() { }
    public static Path directory(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        for (Path current = absolute; current != null; current = current.getParent()) {
            var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther())
                throw new SecurityException("本地目录包含链接或连接点");
        }
        return absolute.toRealPath();
    }
    public static BasicFileAttributes file(Path path) throws IOException {
        directory(path.toAbsolutePath().normalize().getParent());
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther())
            throw new SecurityException("本地来源不是普通文件");
        return attributes;
    }
    public static void requireWritable(Path directory) throws IOException {
        directory(directory);
        if (!Files.isReadable(directory) || !Files.isWritable(directory)) throw new IOException("本地目录不可读写");
        Path probe = Files.createTempFile(directory, ".coffer-write-probe-", ".tmp");
        try {
            try (var output = FileChannel.open(probe, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                output.write(ByteBuffer.wrap(new byte[]{1})); output.force(true);
            }
            if (Files.readAllBytes(probe).length != 1) throw new IOException("本地目录写入校验失败");
        } finally { Files.deleteIfExists(probe); }
    }
}
