package com.coffer.inbox.application;

import com.coffer.auth.service.TenantContext;
import com.coffer.config.InboxImportProperties;
import com.coffer.desktop.DesktopLibraryLayout;
import com.coffer.file.infrastructure.storage.SafeLocalPaths;
import com.coffer.file.storage.StorageKey;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Path;

/** Directory membership is derived from trusted configuration or the desktop owner workspace. */
@Component @RequiredArgsConstructor
public class InboxDirectoryResolver {
    private final InboxImportProperties properties;
    private final ObjectProvider<DesktopLibraryLayout> desktop;
    public Path resolve() {
        if (!properties.isEnabled()) return null;
        if (desktop.getIfAvailable() != null) return desktop.getObject().inbox();
        return resolveTemplate(properties.getDirectory());
    }
    public boolean requiresConfirmation() { return desktop.getIfAvailable() != null; }
    public Path requireSource(Path source) {
        Path inbox = resolve();
        if (source == null || inbox == null) throw new SecurityException("没有受控的收件箱来源");
        Path normalized = source.toAbsolutePath().normalize();
        if (normalized.getParent() == null || !normalized.getParent().equals(inbox.toAbsolutePath().normalize()))
            throw new SecurityException("来源不属于当前用户收件箱");
        StorageKey.requireOwned("users/" + TenantContext.requireOwnerId() + "/inbox/" + normalized.getFileName());
        try { SafeLocalPaths.file(normalized); }
        catch (IOException failed) { throw new IllegalStateException("收件箱来源不可读", failed); }
        return normalized;
    }
    public static Path resolveTemplate(String template) {
        if (template == null || template.isBlank()) return null;
        try {
            Path path = Path.of(template);
            long placeholders = java.util.stream.StreamSupport.stream(path.spliterator(), false)
                    .filter(p -> p.toString().equals("{ownerId}")).count();
            if (!path.isAbsolute() || placeholders != 1 || !template.replace("{ownerId}", "").matches("[^{}]*")) return null;
            for (Path part : path) if (part.toString().equals("..") || part.toString().equals(".")) return null;
            return Path.of(template.replace("{ownerId}", TenantContext.requireOwnerId().toString())).normalize();
        } catch (RuntimeException invalid) { return null; }
    }
}
