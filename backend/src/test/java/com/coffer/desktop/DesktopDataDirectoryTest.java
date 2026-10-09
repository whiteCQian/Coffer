package com.coffer.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static com.coffer.desktop.DesktopStartupException.Reason.*;

class DesktopDataDirectoryTest {
    @TempDir Path temp;

    private Path initialized() throws Exception {
        Path root = temp.resolve(UUID.randomUUID().toString());
        try (var data = DesktopDataDirectory.open(root, true)) {
            assertThat(data.identity().formatVersion()).isEqualTo(1);
            assertThat(data.requiredBackupEntries()).contains(DesktopDataDirectory.KEY_FILE, "library", "database/coffer.mv.db");
            assertThat(data.toString()).doesNotContain(data.masterKey(), root.toString());
        }
        // These layout tests do not open JDBC; the full integration suite uses a real database.
        Files.write(root.resolve("database/coffer.mv.db"), new byte[]{1});
        return root;
    }

    @Test void onlyExplicitInitializationCreatesMissingData() {
        Path root = temp.resolve("missing");
        assertReason(() -> DesktopDataDirectory.open(root, false), NOT_INITIALIZED);
        assertThat(root).doesNotExist();
    }

    @Test void existingNonemptyDirectoryIsNeverInitialized() throws Exception {
        Path root = temp.resolve("occupied"); Files.createDirectory(root);
        Files.writeString(root.resolve("old-file.txt"), "original");
        assertReason(() -> DesktopDataDirectory.open(root, true), INITIALIZATION_REFUSED);
        assertThat(root.resolve(DesktopDataDirectory.KEY_FILE)).doesNotExist();
        assertThat(Files.readString(root.resolve("old-file.txt"))).isEqualTo("original");
    }

    @Test void identityAndKeyRemainStableAndInitializationCannotBeRepeated() throws Exception {
        Path root = initialized();
        var before = Files.readAllBytes(root.resolve(DesktopDataDirectory.KEY_FILE));
        var identity = Files.readString(root.resolve(DesktopDataDirectory.IDENTITY_FILE));
        try (var data = DesktopDataDirectory.open(root, false)) {
            assertThat(data.initializedNow()).isFalse();
        }
        assertReason(() -> DesktopDataDirectory.open(root, true), INITIALIZATION_REFUSED);
        assertThat(Files.readAllBytes(root.resolve(DesktopDataDirectory.KEY_FILE))).isEqualTo(before);
        assertThat(Files.readString(root.resolve(DesktopDataDirectory.IDENTITY_FILE))).isEqualTo(identity);
    }

    @Test void missingLibraryDatabaseAndKeyAreNotReplaced() throws Exception {
        Path root = initialized();
        Path library = root.resolve("library");
        Files.move(library, root.resolve("saved-library"));
        assertReason(() -> DesktopDataDirectory.open(root, false), LIBRARY_MISSING);
        assertThat(library).doesNotExist();
        Files.move(root.resolve("saved-library"), library);
        Files.delete(root.resolve("database/coffer.mv.db"));
        assertReason(() -> DesktopDataDirectory.open(root, false), DATABASE_MISSING);
        assertThat(root.resolve("database/coffer.mv.db")).doesNotExist();
        Files.write(root.resolve("database/coffer.mv.db"), new byte[]{1});
        Files.delete(root.resolve(DesktopDataDirectory.KEY_FILE));
        assertReason(() -> DesktopDataDirectory.open(root, false), KEY_MISSING);
        assertThat(root.resolve(DesktopDataDirectory.KEY_FILE)).doesNotExist();
    }

    @Test void swappedLibraryAndUnsupportedFormatAreRejected() throws Exception {
        Path first = initialized(), second = initialized();
        Path marker = first.resolve("library").resolve(DesktopDataDirectory.LIBRARY_IDENTITY_FILE);
        String original = Files.readString(marker);
        Files.copy(second.resolve("library").resolve(DesktopDataDirectory.LIBRARY_IDENTITY_FILE), marker, StandardCopyOption.REPLACE_EXISTING);
        assertReason(() -> DesktopDataDirectory.open(first, false), BINDING_INVALID);
        Files.writeString(marker, original.replace("\"formatVersion\":1", "\"formatVersion\":99"));
        assertReason(() -> DesktopDataDirectory.open(first, false), FORMAT_UNSUPPORTED);
    }

    @Test void processLockBlocksSecondOwnerUntilClose() throws Exception {
        Path root = initialized();
        try (var first = DesktopDataDirectory.open(root, false)) {
            assertReason(() -> DesktopDataDirectory.open(root, false), IN_USE);
        }
        try (var next = DesktopDataDirectory.open(root, false)) { assertThat(next.identity()).isNotNull(); }
    }

    @Test void truncatedKeyAndPartialInitializationAreRejected() throws Exception {
        Path root = initialized();
        Files.writeString(root.resolve(DesktopDataDirectory.KEY_FILE), "truncated");
        assertReason(() -> DesktopDataDirectory.open(root, false), KEY_INVALID);
        Path partial = temp.resolve("partial"); Files.createDirectories(partial.resolve("library"));
        assertReason(() -> DesktopDataDirectory.open(partial, true), INITIALIZATION_REFUSED);
        assertReason(() -> DesktopDataDirectory.open(partial, false), BINDING_INVALID);
    }

    @Test void defaultPathAndForcedProductionPropertiesAreResolvedSafely() {
        var env = new org.springframework.mock.env.MockEnvironment();
        env.setActiveProfiles("desktop"); env.setProperty("LOCALAPPDATA", temp.toString());
        assertThat(DesktopEnvironmentPostProcessor.resolveDirectory(env)).isEqualTo(temp.resolve("Coffer"));
        env.setProperty("coffer.desktop.data-directory", "relative-path");
        assertReason(() -> DesktopEnvironmentPostProcessor.resolveDirectory(env), INVALID_DIRECTORY);
        env.setActiveProfiles("desktop", "dev");
        assertReason(() -> new DesktopEnvironmentPostProcessor().postProcessEnvironment(env,
                new org.springframework.boot.SpringApplication()), PROFILE_CONFLICT);
    }

    @Test @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void readonlyEmptyDirectoryIsRejectedBeforePermissionsOrKeysAreChanged() throws Exception {
        Path root = Files.createDirectory(temp.resolve("readonly-empty"));
        var acl = Files.getFileAttributeView(root, java.nio.file.attribute.AclFileAttributeView.class);
        var original = acl.getAcl();
        var current = root.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        var deny = java.nio.file.attribute.AclEntry.newBuilder().setType(java.nio.file.attribute.AclEntryType.DENY)
                .setPrincipal(current).setPermissions(java.nio.file.attribute.AclEntryPermission.WRITE_DATA,
                        java.nio.file.attribute.AclEntryPermission.APPEND_DATA).build();
        var denied = new java.util.ArrayList<java.nio.file.attribute.AclEntry>(); denied.add(deny); denied.addAll(original);
        try {
            acl.setAcl(denied);
            assertReason(() -> DesktopDataDirectory.open(root, true), IO_FAILED);
            assertThat(root.resolve(DesktopDataDirectory.KEY_FILE)).doesNotExist();
            assertThat(acl.getAcl()).contains(deny);
        } finally { acl.setAcl(original); }
    }

    private void assertReason(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, DesktopStartupException.Reason reason) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DesktopStartupException.class, failure -> assertThat(failure.reason()).isEqualTo(reason));
    }
}
