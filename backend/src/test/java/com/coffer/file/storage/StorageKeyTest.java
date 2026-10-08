package com.coffer.file.storage;

import com.coffer.auth.service.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageKeyTest {
    @AfterEach void clearOwner() { TenantContext.clear(); }

    @Test void requiresCurrentOwnerAndCanonicalSegments() {
        TenantContext.set(7L);
        assertThat(StorageKey.requireOwned("users/7/files/2026/采购合同.pdf"))
                .isEqualTo("users/7/files/2026/采购合同.pdf");
        assertThatThrownBy(() -> StorageKey.requireOwned("users/8/files/x"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> StorageKey.requireOwned("users/07/files/x"))
                .isInstanceOf(AccessDeniedException.class);
        TenantContext.clear();
        assertThatThrownBy(() -> StorageKey.requireOwned("users/7/files/x"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test void rejectsTraversalWindowsNamesAndNonCanonicalUnicode() {
        TenantContext.set(7L);
        for (String key : new String[] {
                "users/7/../x", "users/7/./x", "users/7//x", "users/7/x/",
                "users/7/x\\y", "users/7/CON.txt", "users/7/lPt9.bin",
                "users/7/x.", "users/7/x ", "users/7/x:y", "users/7/x?y",
                "users/7/x\u0000y", "users/7/e\u0301.txt", "users/7/" + "x".repeat(256),
                "users/7/" + "x/".repeat(520) + "end"
        }) {
            assertThatThrownBy(() -> StorageKey.requireOwned(key))
                    .as("key %s must be rejected", key)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
