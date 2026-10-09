package com.coffer.desktop;

import com.coffer.auth.service.*;
import com.coffer.file.application.LocalImportSource;
import com.coffer.file.infrastructure.storage.SafeLocalPaths;
import com.coffer.file.storage.*;
import com.coffer.inbox.application.InboxImportScanner;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.UUID;

/** Only the shell's native capability can submit a selected path to the controller. */
@Service @Profile("desktop") @RequiredArgsConstructor @OwnerOnly
public class NativeInboxService {
    private final DesktopLibraryLayout layout;
    private final FileStoragePort storage;
    private final InboxImportScanner scanner;
    private final DesktopDataDirectory directory;
    public record Preview(String name, long size, long modifiedMillis, String fileKey, String sha256, String targetPath) { }

    public Preview preview(String selectedPath) {
        Path source = source(selectedPath);
        try {
            var attrs = SafeLocalPaths.file(source);
            String name = Normalizer.normalize(source.getFileName().toString(), Normalizer.Form.NFC);
            String target = target(name); layout.ensureOwnerWorkspace();
            if (storage.exists(target)) {
                int dot = name.lastIndexOf('.');
                name = (dot <= 0 ? name : name.substring(0, dot)) + "_(" + UUID.randomUUID().toString().substring(0, 8) + ")" + (dot <= 0 ? "" : name.substring(dot));
                target = target(name);
            }
            try (var input = LocalImportSource.open(source, attrs.size(), attrs.lastModifiedTime().toMillis(), LocalImportSource.key(attrs))) {
                return new Preview(source.getFileName().toString(), attrs.size(), attrs.lastModifiedTime().toMillis(), LocalImportSource.key(attrs), input.sha256(), target);
            }
        } catch (IOException failure) { throw new IllegalStateException("所选文件不可读或被占用，请关闭外部应用后重试", failure); }
    }

    public void commit(String selectedPath, Preview confirmed) {
        if (confirmed == null || confirmed.sha256() == null || !confirmed.sha256().matches("[0-9a-f]{64}")) throw new IllegalArgumentException("导入预览身份无效");
        String prefix = "users/" + TenantContext.requireOwnerId() + "/inbox/";
        StorageKey.requireOwned(confirmed.targetPath());
        if (!confirmed.targetPath().startsWith(prefix) || confirmed.targetPath().substring(prefix.length()).contains("/")) throw new IllegalArgumentException("导入目标必须是当前用户收件箱中的文件");
        Path source = source(selectedPath); layout.ensureOwnerWorkspace();
        try (var input = LocalImportSource.open(source, confirmed.size(), confirmed.modifiedMillis(), confirmed.fileKey())) {
            if (!input.sha256().equals(confirmed.sha256())) throw new StorageConflictException("所选文件正文已变化，请重新预览");
            storage.writeVerified(confirmed.targetPath(), input.stream(), null, confirmed.size(), confirmed.sha256());
            input.verify();
        } catch (IOException failure) { throw new IllegalStateException("复制到收件箱失败，来源文件已保留，请检查占用和空间", failure); }
        // No model call or formal registration here. R31 still requires the displayed final path confirmation.
        scanner.scanOnce();
    }
    private String target(String name) { return StorageKey.requireOwned("users/" + TenantContext.requireOwnerId() + "/inbox/" + name); }
    private Path source(String value) {
        if (value == null || value.length() > 4096) throw new IllegalArgumentException("所选文件路径无效");
        Path path = Path.of(value);
        if (!path.isAbsolute()) throw new IllegalArgumentException("所选文件必须是本机绝对路径");
        try {
            SafeLocalPaths.file(path);Path real=path.toRealPath();
            if(real.startsWith(directory.root().toRealPath()) || real.startsWith(directory.libraryRoot().toRealPath()))
                throw new org.springframework.security.access.AccessDeniedException("数据目录与文件库内的文件只能通过受控业务入口访问，请选择外部来源文件");
            return real;
        } catch(IOException failed) { throw new IllegalStateException("所选本机文件不可读或路径不受控",failed); }
    }
}
