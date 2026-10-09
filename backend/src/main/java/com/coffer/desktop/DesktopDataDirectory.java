package com.coffer.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.DisposableBean;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.util.*;
import static com.coffer.desktop.DesktopStartupException.Reason.*;

/** One process owns the whole data set, not just individual JDBC connections. */
public final class DesktopDataDirectory implements DisposableBean, AutoCloseable {
    public static final String IDENTITY_FILE = ".coffer-desktop.json";
    public static final String LIBRARY_IDENTITY_FILE = ".coffer-library.json";
    public static final String KEY_FILE = ".coffer-encryption-key";
    public static final String SETUP_TOKEN_FILE = "coffer-initial-admin-token.txt";
    private static final String LOCK_FILE = ".coffer-desktop.lock";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path root;
    private final Path libraryRoot;
    private final boolean rebindRequested;
    private final boolean initializedNow;
    private final Identity identity;
    private final String masterKey;
    private final String setupToken;
    private final FileChannel channel;
    private final FileLock lock;
    private final FileChannel libraryChannel;
    private final FileLock libraryLock;

    public record Identity(int formatVersion, String installationId, String libraryId) { }

    private DesktopDataDirectory(Path root, boolean fresh, Identity identity, String masterKey,
                                 String setupToken, FileChannel channel, FileLock lock,
                                 Path libraryRoot, boolean rebindRequested, FileChannel libraryChannel, FileLock libraryLock) {
        this.root = root; this.initializedNow = fresh; this.identity = identity;
        this.masterKey = masterKey; this.setupToken = setupToken; this.channel = channel; this.lock = lock;
        this.libraryRoot = libraryRoot; this.rebindRequested = rebindRequested;
        this.libraryChannel = libraryChannel; this.libraryLock = libraryLock;
    }

    public static DesktopDataDirectory open(Path directory, boolean initialize) {
        return open(directory, initialize, directory.toAbsolutePath().normalize().resolve("library"), false);
    }

    public static DesktopDataDirectory open(Path directory, boolean initialize, Path configuredLibrary, boolean rebind) {
        Path root = directory.toAbsolutePath().normalize();
        Path library = configuredLibrary.toAbsolutePath().normalize();
        FileChannel channel = null;
        FileLock lock = null;
        FileChannel libraryChannel = null;
        FileLock libraryLock = null;
        try {
            if ((library.startsWith(root) && !library.equals(root.resolve("library"))) || root.startsWith(library)
                    || (initialize && rebind)) throw new DesktopStartupException(INVALID_DIRECTORY);
            verifyAncestors(root);
            verifyAncestors(library);
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                if (!initialize) throw new DesktopStartupException(NOT_INITIALIZED);
                Files.createDirectories(root); restrict(root);
            }
            verifyDirectory(root);
            if (initialize) {
                try (var entries = Files.list(root)) {
                    if (entries.anyMatch(p -> !p.getFileName().toString().equals(LOCK_FILE)))
                        throw new DesktopStartupException(INITIALIZATION_REFUSED);
                }
                com.coffer.file.infrastructure.storage.SafeLocalPaths.requireWritable(root);
                restrict(root);
            }
            Path lockPath = root.resolve(LOCK_FILE);
            if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) requireFile(lockPath, BINDING_INVALID);
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { throw new DesktopStartupException(IN_USE); }
            if (lock == null) throw new DesktopStartupException(IN_USE);
            Identity identity;
            if (initialize) {
                // Recheck under the lock so simultaneous initialization cannot replace another data set.
                try (var entries = Files.list(root)) {
                    if (entries.anyMatch(p -> !p.getFileName().toString().equals(LOCK_FILE)))
                        throw new DesktopStartupException(INITIALIZATION_REFUSED);
                }
                identity = new Identity(1, UUID.randomUUID().toString(), UUID.randomUUID().toString());
                if (Files.exists(library, LinkOption.NOFOLLOW_LINKS)) {
                    verifyDirectory(library);
                    try (var entries = Files.list(library)) {
                        if (entries.anyMatch(p -> !p.getFileName().toString().equals(".coffer-library.lock")))
                            throw new DesktopStartupException(INITIALIZATION_REFUSED);
                    }
                } else Files.createDirectories(library);
            } else {
                requireFile(root.resolve(IDENTITY_FILE), BINDING_INVALID);
                identity = readIdentity(root.resolve(IDENTITY_FILE));
                verifyDirectory(library, LIBRARY_MISSING);
                requireFile(library.resolve(LIBRARY_IDENTITY_FILE), BINDING_INVALID);
                if (!identity.equals(readIdentity(library.resolve(LIBRARY_IDENTITY_FILE))))
                    throw new DesktopStartupException(BINDING_INVALID);
                verifyDirectory(root.resolve("database"), DATABASE_MISSING);
                requireFile(root.resolve("database/coffer.mv.db"), DATABASE_MISSING);
                if (Files.size(root.resolve("database/coffer.mv.db")) == 0)
                    throw new DesktopStartupException(DATABASE_INVALID);
            }
            com.coffer.file.infrastructure.storage.SafeLocalPaths.requireWritable(library);
            Path libraryLockPath = library.resolve(".coffer-library.lock");
            if (Files.exists(libraryLockPath, LinkOption.NOFOLLOW_LINKS)) requireFile(libraryLockPath, BINDING_INVALID);
            libraryChannel = FileChannel.open(libraryLockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try { libraryLock = libraryChannel.tryLock(); }
            catch (OverlappingFileLockException busy) { throw new DesktopStartupException(IN_USE); }
            if (libraryLock == null) throw new DesktopStartupException(IN_USE);
            if (initialize) {
                try (var entries = Files.list(library)) {
                    if (entries.anyMatch(p -> !p.getFileName().toString().equals(".coffer-library.lock")))
                        throw new DesktopStartupException(INITIALIZATION_REFUSED);
                }
                restrict(library);
                Files.createDirectory(root.resolve("database"));
                writeNew(root.resolve(KEY_FILE), randomSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                writeNew(root.resolve(SETUP_TOKEN_FILE), randomSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                writeNew(library.resolve(LIBRARY_IDENTITY_FILE), JSON.writeValueAsBytes(identity));
                writeNew(root.resolve(IDENTITY_FILE), JSON.writeValueAsBytes(identity));
            }
            requireFile(root.resolve(KEY_FILE), KEY_MISSING);
            String key = readSecret(root.resolve(KEY_FILE));
            String token = Files.exists(root.resolve(SETUP_TOKEN_FILE), LinkOption.NOFOLLOW_LINKS)
                    ? readSecret(root.resolve(SETUP_TOKEN_FILE)) : "";
            return new DesktopDataDirectory(root, initialize, identity, key, token, channel, lock,
                    library.toRealPath(), rebind, libraryChannel, libraryLock);
        } catch (DesktopStartupException fixed) {
            release(libraryLock, libraryChannel);
            release(lock, channel); throw fixed;
        } catch (IOException | RuntimeException failed) {
            release(libraryLock, libraryChannel);
            release(lock, channel); throw new DesktopStartupException(IO_FAILED);
        }
    }

    private static Identity readIdentity(Path file) throws IOException {
        if (Files.size(file) > 4096) throw new DesktopStartupException(BINDING_INVALID);
        Identity identity;
        try { identity = JSON.readValue(Files.readAllBytes(file), Identity.class); }
        catch (Exception invalid) { throw new DesktopStartupException(BINDING_INVALID); }
        if (identity.formatVersion() != 1) throw new DesktopStartupException(FORMAT_UNSUPPORTED);
        try {
            if (!UUID.fromString(identity.installationId()).toString().equals(identity.installationId())
                    || !UUID.fromString(identity.libraryId()).toString().equals(identity.libraryId()))
                throw new IllegalArgumentException();
        }
        catch (Exception invalid) { throw new DesktopStartupException(BINDING_INVALID); }
        return identity;
    }

    private static String readSecret(Path file) throws IOException {
        requireFile(file, KEY_INVALID);
        if (Files.size(file) > 256) throw new DesktopStartupException(KEY_INVALID);
        String secret = Files.readString(file).strip();
        try {
            if (Base64.getDecoder().decode(secret).length != 32) throw new DesktopStartupException(KEY_INVALID);
        } catch (IllegalArgumentException invalid) { throw new DesktopStartupException(KEY_INVALID); }
        return secret;
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static void writeNew(Path file, byte[] bytes) throws IOException {
        try (var output = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            restrict(file);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) output.write(buffer);
            output.force(true);
        }
    }

    static void restrict(Path path) throws IOException {
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"));
            return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (Files.isDirectory(path)) entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
            acl.setAcl(List.of(entry.build()));
        }
    }

    private static void verifyAncestors(Path path) throws IOException {
        for (Path parent = path; parent != null; parent = parent.getParent())
            if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) verifyDirectory(parent);
    }
    private static void verifyDirectory(Path path) throws IOException { verifyDirectory(path, INVALID_DIRECTORY); }
    private static void verifyDirectory(Path path, DesktopStartupException.Reason reason) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new DesktopStartupException(reason);
        var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()) throw new DesktopStartupException(reason);
    }
    private static void requireFile(Path path, DesktopStartupException.Reason reason) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new DesktopStartupException(reason);
        var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()) throw new DesktopStartupException(reason);
    }

    public Path root() { return root; }
    public Path libraryRoot() { return libraryRoot; }
    public boolean rebindRequested() { return rebindRequested; }
    public Identity identity() { return identity; }
    public boolean initializedNow() { return initializedNow; }
    String masterKey() { return masterKey; }
    String setupToken() { return setupToken; }
    public List<String> requiredBackupEntries() {
        var entries = new ArrayList<>(List.of("database/coffer.mv.db",
                libraryRoot.equals(root.resolve("library")) ? "library" : libraryRoot.toString(), IDENTITY_FILE, KEY_FILE));
        if (Files.exists(root.resolve(SETUP_TOKEN_FILE), LinkOption.NOFOLLOW_LINKS)) entries.add(SETUP_TOKEN_FILE);
        return List.copyOf(entries);
    }
    @Override public String toString() { return "DesktopDataDirectory[redacted]"; }
    @Override public void destroy() { close(); }
    @Override public void close() { release(libraryLock, libraryChannel); release(lock, channel); }
    private static void release(FileLock lock, FileChannel channel) {
        try { if (lock != null && lock.isValid()) lock.release(); } catch (IOException ignored) { }
        try { if (channel != null) channel.close(); } catch (IOException ignored) { }
    }
}
