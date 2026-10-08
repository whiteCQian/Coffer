package com.coffer.operations;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;

@Component @Profile("desktop")
public class LocalStorageProbe implements StorageProbe {
    private final Path root;
    public LocalStorageProbe(@Value("${coffer.storage.local.root:}") String root) { this.root = Path.of(root).toAbsolutePath().normalize(); }
    public RuntimeMonitor.Component check() {
        Path probe = null;
        boolean usable = false;
        try {
            for (Path ancestor = root; ancestor != null; ancestor = ancestor.getParent()) {
                var attributes = Files.readAttributes(ancestor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) throw new IllegalStateException();
            }
            probe = Files.createTempFile(root, ".coffer-health-", ".tmp");
            try (var channel = FileChannel.open(probe, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.write(ByteBuffer.wrap(new byte[]{1})); channel.force(true);
            }
            try (var input = Files.newInputStream(probe, LinkOption.NOFOLLOW_LINKS)) {
                usable = java.util.Arrays.equals(input.readNBytes(2), new byte[]{1});
            }
        } catch (Exception ignored) { usable = false; }
        finally {
            if (probe != null) try { Files.deleteIfExists(probe); } catch (Exception ignored) { usable = false; }
        }
        return new RuntimeMonitor.Component("storage", usable ? "UP" : "DOWN",
                usable ? "OK" : "STORAGE_UNAVAILABLE", usable ? "NONE" : "CHECK_STORAGE", null, null);
    }
}
