package com.coffer.file.storage;

import com.coffer.auth.service.TenantContext;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

/** Validates a canonical logical key before it can reach any storage adapter. */
public final class StorageKey {
    private static final int MAX_KEY_BYTES = 1024;
    private static final int MAX_SEGMENT_BYTES = 255;

    private StorageKey() { }

    public static String requireOwned(String key) {
        long ownerId = TenantContext.requireOwnerId();
        if (key == null || key.isEmpty() || key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES
                || !Normalizer.isNormalized(key, Normalizer.Form.NFC)) {
            throw new IllegalArgumentException("无效的存储路径");
        }
        String prefix = "users/" + ownerId + "/";
        if (!key.startsWith(prefix) || key.length() == prefix.length()) {
            throw new org.springframework.security.access.AccessDeniedException("无权访问存储对象");
        }
        for (String segment : key.split("/", -1)) {
            requireSegment(segment);
        }
        return key;
    }

    private static void requireSegment(String segment) {
        if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                || segment.getBytes(StandardCharsets.UTF_8).length > MAX_SEGMENT_BYTES
                || segment.endsWith(".") || segment.endsWith(" ")) {
            throw new IllegalArgumentException("无效的存储路径");
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (Character.isISOControl(c) || "\\<>:\"|?*".indexOf(c) >= 0) {
                throw new IllegalArgumentException("无效的存储路径");
            }
        }
        String base = segment.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (base.equals("CON") || base.equals("PRN") || base.equals("AUX") || base.equals("NUL")
                || base.equals("CONIN$") || base.equals("CONOUT$")
                || base.matches("COM[1-9¹²³]") || base.matches("LPT[1-9¹²³]")) {
            throw new IllegalArgumentException("无效的存储路径");
        }
    }
}
