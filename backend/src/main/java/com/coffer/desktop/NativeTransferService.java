package com.coffer.desktop;

import com.coffer.auth.service.*;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.file.infrastructure.storage.SafeLocalPaths;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import java.nio.file.*;
import java.util.UUID;

/** Persistent handoff consent. Cleanup requires committed metadata and a verified managed body. */
@Service @Profile("desktop") @RequiredArgsConstructor @OwnerOnly
public class NativeTransferService {
    private final JdbcTemplate jdbc;
    private final FileStoragePort storage;
    private final DesktopDataDirectory directory;
    public String begin(Path source, NativeInboxService.Preview preview) {
        String id = UUID.randomUUID().toString();
        Path claimed = source.resolveSibling(".coffer-import-" + id);
        jdbc.update("INSERT INTO desktop_native_transfer(id,owner_id,inbox_key,source_path,claimed_path,source_size,modified_millis,file_key,sha256,state,created_at) VALUES(?,?,?,?,?,?,?,?,?,'PREPARING',CURRENT_TIMESTAMP)",
                id, TenantContext.requireOwnerId(), preview.targetPath(), source.toString(), claimed.toString(), preview.size(), preview.modifiedMillis(), preview.fileKey(), preview.sha256());
        return id;
    }
    public void staged(String id) {
        jdbc.update("UPDATE desktop_native_transfer SET state='STAGED' WHERE id=? AND owner_id=? AND state='PREPARING'", id, TenantContext.requireOwnerId());
    }
    @OwnerScheduled @Scheduled(fixedDelayString="${coffer.desktop.transfer-cleanup-delay-ms:30000}")
    public synchronized void recover() {
        long owner = TenantContext.requireOwnerId();
        // PREPARING cannot prove publication succeeded: a concurrent same-name copy may have won.
        // An interrupted/failed staging operation always retains its external source.
        for (var transfer : jdbc.queryForList("SELECT * FROM desktop_native_transfer WHERE owner_id=? AND state IN ('STAGED','REMOVING')", owner)) {
            String id = (String) transfer.get("id"), inbox = (String) transfer.get("inbox_key"), sha = (String) transfer.get("sha256");
            long size = ((Number) transfer.get("source_size")).longValue();
            // No cleanup on a duplicate/unconfirmed/failed import, nor on a rolled-back upload transaction.
            var registered = jdbc.queryForList("SELECT f.storage_path,f.file_size,f.content_sha256 FROM inbox_import_record i JOIN file_metadata f ON f.id=i.file_id AND f.owner_id=i.owner_id WHERE i.owner_id=? AND i.source_file_name=? AND i.status='IMPORTED' AND i.content_sha256=?", owner, Path.of(inbox).getFileName().toString(), sha);
            if (registered.size() != 1) continue;
            String warning = null;
            try {
                var file = registered.get(0);
                var object = storage.stat((String) file.get("storage_path"));
                if (((Number) file.get("file_size")).longValue() != size || object.size() != size || !sha.equals(file.get("content_sha256")) || !sha.equals(object.sha256())) throw new IllegalStateException("Managed body mismatch");
                Path source = Path.of((String) transfer.get("source_path"));
                SafeLocalPaths.directory(source.getParent());
                Path realParent = source.getParent().toRealPath();
                if (realParent.startsWith(directory.root().toRealPath()) || realParent.startsWith(directory.libraryRoot().toRealPath())) throw new SecurityException("Protected source directory");
                jdbc.update("UPDATE desktop_native_transfer SET state='REMOVING' WHERE id=? AND owner_id=?", id, owner);
                NativeSourceRemoval.remove(source, Path.of((String) transfer.get("claimed_path")), size, ((Number) transfer.get("modified_millis")).longValue(), (String) transfer.get("file_key"), sha);
                jdbc.update("UPDATE desktop_native_transfer SET state='COMPLETED',last_error=NULL WHERE id=? AND owner_id=?", id, owner);
            } catch (Exception retained) {
                warning = "文件已入库，但来源已变化、被占用或无删除权限；来源保留，请核对后手动处理（接管记录 " + id + "）";
                jdbc.update("UPDATE desktop_native_transfer SET state='RETAINED',last_error=? WHERE id=? AND owner_id=?", warning, id, owner);
            }
            jdbc.update("UPDATE inbox_import_record SET last_error=? WHERE owner_id=? AND source_file_name=? AND status='IMPORTED' AND content_sha256=?", warning, owner, Path.of(inbox).getFileName().toString(), sha);
        }
    }
}
