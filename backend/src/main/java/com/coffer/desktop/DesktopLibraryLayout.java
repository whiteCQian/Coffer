package com.coffer.desktop;

import com.coffer.auth.service.OwnerAuthorization;
import com.coffer.file.infrastructure.storage.SafeLocalPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.List;

@Component @Profile("desktop") @RequiredArgsConstructor
public class DesktopLibraryLayout {
    public static final List<String> AREAS = List.of("inbox", "managed", "work", "quarantine", "lost-found");
    private final DesktopDataDirectory directory;
    private final OwnerAuthorization authorization;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private record OwnerIdentity(int formatVersion, String libraryId, long ownerId) { }

    public synchronized Path ensureOwnerWorkspace() {
        long owner = authorization.requireOwner();
        Path root = directory.libraryRoot();
        Path ownerRoot = root.resolve("users").resolve(Long.toString(owner));
        try {
            SafeLocalPaths.directory(root);
            if (!Files.exists(ownerRoot, LinkOption.NOFOLLOW_LINKS)
                    && jdbc.queryForObject("SELECT COUNT(*) FROM file_metadata WHERE owner_id=?", Long.class, owner) > 0)
                throw new IllegalStateException("用户文件目录缺失，请恢复原文件库");
            Path users = root.resolve("users");
            if (!Files.exists(users, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(users);
            SafeLocalPaths.directory(users);
            if (!Files.exists(ownerRoot, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(ownerRoot);
            SafeLocalPaths.directory(ownerRoot);
            var expected = new OwnerIdentity(1, directory.identity().libraryId(), owner);
            Path marker = ownerRoot.resolve(".coffer-owner.json");
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                SafeLocalPaths.file(marker);
                if (Files.size(marker) > 4096 || !expected.equals(json.readValue(Files.readAllBytes(marker), OwnerIdentity.class)))
                    throw new IllegalStateException("用户文件库绑定不一致");
                for (String area : AREAS) SafeLocalPaths.directory(ownerRoot.resolve(area));
            } else {
                // Only empty workspaces or recognized R30 legacy areas can acquire a new owner marker.
                try (var entries = Files.list(ownerRoot)) {
                    var existing = entries.toList();
                    if (!existing.isEmpty() && (jdbc.queryForObject("SELECT COUNT(*) FROM file_metadata WHERE owner_id=?", Long.class, owner) == 0
                            || existing.stream().anyMatch(p -> !List.of("files", "archive").contains(p.getFileName().toString()))))
                        throw new IllegalStateException("用户目录非空，不能初始化为新文件库");
                    for (Path legacy : existing) SafeLocalPaths.directory(legacy);
                }
                SafeLocalPaths.requireWritable(ownerRoot);
                for (String area : AREAS) Files.createDirectory(ownerRoot.resolve(area));
                byte[] bytes = json.writeValueAsBytes(expected);
                try (var output = FileChannel.open(marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) output.write(buffer); output.force(true);
                }
            }
            return ownerRoot;
        } catch (IOException failed) { throw new IllegalStateException("用户文件目录不可读写，请检查权限", failed); }
    }
    public Path inbox() { return ensureOwnerWorkspace().resolve("inbox"); }
}
