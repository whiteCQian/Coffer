package com.coffer.util;

import java.util.Locale;
import java.util.regex.Pattern;

/** Preserve business names; only the separately allocated collision counter is shortened. */
public final class ArchiveFileName {
    // The counter is one-based: 0001 .. ZZZZ in base 36. The database stores zero-based values.
    public static final int MAX_SEQUENCE_NUMBER = 36 * 36 * 36 * 36 - 2;
    private static final Pattern LEGACY_UUID = Pattern.compile(
            "(?i)[-_ ][0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern COUNTER = Pattern.compile("-[0-9A-Z]{4}$");
    private ArchiveFileName() { }

    public static String base(String name) {
        if (name == null || name.isBlank()) return "unnamed";
        int dot = extensionOffset(name);
        String stem = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "" : name.substring(dot);
        String clean = LEGACY_UUID.matcher(stem).replaceAll("");
        return (clean.isBlank() ? "unnamed" : clean) + ext;
    }

    public static String numbered(String baseName, int sequenceNumber) {
        if (sequenceNumber < 0 || sequenceNumber > MAX_SEQUENCE_NUMBER)
            throw new IllegalArgumentException("防重编号已超出4字符范围");
        String value = Integer.toString(sequenceNumber + 1, 36).toUpperCase(Locale.ROOT);
        String counter = "0".repeat(4 - value.length()) + value;
        String name = base(baseName);
        int dot = extensionOffset(name);
        return dot < 0 ? name + "-" + counter : name.substring(0, dot) + "-" + counter + name.substring(dot);
    }

    public static String editableName(String name, String path) {
        if (name == null || path == null || !name.equals(fromPath(path))) return name;
        int dot = extensionOffset(name);
        String stem = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "" : name.substring(dot);
        return COUNTER.matcher(stem).replaceAll("") + ext;
    }

    public static String sourceName(String name, String path, boolean archived) {
        String businessName = archived && path != null && path.contains("/archive/") ? editableName(name, path) : name;
        return base(businessName);
    }

    public static String fromPath(String path) {
        if (path == null) throw new IllegalArgumentException("归档路径不能为空");
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static int extensionOffset(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? dot : -1;
    }
}
