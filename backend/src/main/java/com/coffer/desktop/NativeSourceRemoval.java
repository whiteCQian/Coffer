package com.coffer.desktop;

import com.coffer.file.application.LocalImportSource;
import com.coffer.file.infrastructure.storage.SafeLocalPaths;
import com.coffer.file.storage.StorageConflictException;
import java.io.IOException;
import java.nio.file.*;

/** Claim the exact confirmed source before removal; a replaced path is never deleted. */
final class NativeSourceRemoval {
    static void remove(Path source, Path claimed, long size, long modified, String key, String sha) throws IOException {
        SafeLocalPaths.directory(source.getParent());
        if (!source.getParent().equals(claimed.getParent())) throw new IOException("Invalid source claim");
        if (!Files.exists(claimed, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return;
            verify(source, size, modified, key, sha);
            // No overwrite. The persisted random sibling name survives a crash between claim and delete.
            Files.move(source, claimed);
        }
        try {
            verify(claimed, size, modified, key, sha);
        } catch (IOException | RuntimeException changed) {
            // Restore without replacing anything created at the original path in the meantime.
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) Files.move(claimed, source);
            throw changed;
        }
        Files.delete(claimed);
    }
    private static void verify(Path path, long size, long modified, String key, String sha) throws IOException {
        try (var input = LocalImportSource.open(path, size, modified, key)) {
            if (!sha.equals(input.sha256())) throw new StorageConflictException("来源内容已变化，已保留来源文件");
            input.verify();
        }
    }
    private NativeSourceRemoval() { }
}
