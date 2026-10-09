package com.coffer.desktop;

import com.coffer.service.SecretCryptoService;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.coffer.desktop.DesktopStartupException.Reason.*;

@Configuration(proxyBeanMethods = false) @Profile("desktop")
public class DesktopDataConfiguration {
    @Bean FlywayMigrationStrategy desktopMigrations(DesktopDataDirectory directory, SecretCryptoService crypto) {
        return flyway -> {
            var jdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
            try {
                // Existing data must match before any schema upgrades are performed.
                if (!directory.initializedNow()) verifyBinding(jdbc, directory, crypto, false);
                flyway.migrate();
                if (directory.initializedNow()) {
                    var id = directory.identity();
                    jdbc.update("INSERT INTO desktop_library_binding(id,format_version,installation_id,library_id,key_check) VALUES(1,?,?,?,?)",
                            id.formatVersion(), id.installationId(), id.libraryId(), crypto.encrypt(checkValue(id)));
                }
                verifyBinding(jdbc, directory, crypto, true);
                verifyLibraryRoot(jdbc, directory);
                Long accounts = jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Long.class);
                if (accounts != null && accounts > 0)
                    java.nio.file.Files.deleteIfExists(directory.root().resolve(DesktopDataDirectory.SETUP_TOKEN_FILE));
                else if (directory.setupToken().isBlank()) throw new DesktopStartupException(SETUP_TOKEN_MISSING);
            } catch (DesktopStartupException fixed) { throw fixed; }
            catch (Exception failed) { throw new DesktopStartupException(DATABASE_INVALID); }
        };
    }

    static void verifyBinding(JdbcTemplate jdbc, DesktopDataDirectory directory, SecretCryptoService crypto, boolean rewrap) {
        var rows = jdbc.query("SELECT format_version,installation_id,library_id,key_check FROM desktop_library_binding WHERE id=1",
                (result, n) -> new Binding(result.getInt(1), result.getString(2), result.getString(3), result.getString(4)));
        var id = directory.identity();
        if (rows.size() != 1) throw new DesktopStartupException(BINDING_INVALID);
        var row = rows.get(0);
        if (row.version() != id.formatVersion() || !id.installationId().equals(row.installation()) || !id.libraryId().equals(row.library()))
            throw new DesktopStartupException(BINDING_INVALID);
        String proof;
        try { proof = crypto.decrypt(row.keyCheck()); }
        catch (Exception invalid) { throw new DesktopStartupException(KEY_MISMATCH); }
        if (!checkValue(id).equals(proof)) throw new DesktopStartupException(KEY_MISMATCH);
        if (rewrap && !crypto.authenticatedWithCurrentKey(row.keyCheck()))
            jdbc.update("UPDATE desktop_library_binding SET key_check=? WHERE id=1", crypto.encrypt(proof));
    }

    private static String checkValue(DesktopDataDirectory.Identity id) { return "coffer-desktop-v1:" + id.installationId() + ":" + id.libraryId(); }
    private static void verifyLibraryRoot(JdbcTemplate jdbc, DesktopDataDirectory directory) {
        String stored = jdbc.queryForObject("SELECT library_root FROM desktop_library_binding WHERE id=1", String.class);
        String selected = directory.libraryRoot().toString();
        if (selected.equals(stored)) return;
        boolean legacyDefault = stored == null && directory.libraryRoot().equals(directory.root().resolve("library"));
        if (!directory.initializedNow() && !legacyDefault && !directory.rebindRequested())
            throw new DesktopStartupException(REBIND_REQUIRED);
        if (!directory.initializedNow() && !legacyDefault) {
            long pending = jdbc.queryForObject("SELECT COUNT(*) FROM file_write_intent WHERE status IN ('PREPARED','OBJECT_WRITTEN','FAILED','MANUAL_REVIEW','DISCARD_PENDING','DISCARDING')", Long.class)
                    + jdbc.queryForObject("SELECT COUNT(*) FROM inbox_import_record WHERE status='IMPORTING'", Long.class)
                    + jdbc.queryForObject("SELECT COUNT(*) FROM file_rename_intent WHERE status IN ('PREPARED','FAILED','MANUAL_REVIEW')", Long.class)
                    + jdbc.queryForObject("SELECT COUNT(*) FROM work_save_intent WHERE status IN ('PREPARED','OBJECT_WRITTEN','MANUAL_REVIEW')", Long.class)
                    + jdbc.queryForObject("SELECT COUNT(*) FROM desktop_work_copy WHERE status NOT IN ('CLOSED','DISCARDED')", Long.class)
                    + jdbc.queryForObject("SELECT COUNT(*) FROM storage_deletion_task WHERE status <> 'SUCCEEDED'", Long.class)
                    + jdbc.queryForObject("SELECT COUNT(*) FROM archive_operation_item WHERE execution_status NOT IN ('SUCCEEDED','SKIPPED') OR rollback_status NOT IN ('NOT_REQUESTED','SUCCEEDED','NOT_REVERSIBLE')", Long.class);
            if (pending > 0) throw new DesktopStartupException(REBIND_PENDING);
            var storage = new com.coffer.file.infrastructure.storage.LocalFileStorageAdapter(selected);
            Long cursor = 0L;
            for (;;) {
                var rows = jdbc.queryForList("SELECT id,owner_id,storage_path,file_size,content_sha256 FROM file_metadata WHERE owner_id IS NOT NULL AND id>? ORDER BY id LIMIT 200", cursor);
                if (rows.isEmpty()) break;
                for (var row : rows) {
                    try {
                        long owner = ((Number) row.get("owner_id")).longValue();
                        var observed = com.coffer.auth.service.TenantContext.supplyAs(owner, () -> storage.stat((String) row.get("storage_path")));
                        if (observed.size() != ((Number) row.get("file_size")).longValue()
                                || !observed.sha256().equals(row.get("content_sha256"))) throw new IllegalStateException();
                    } catch (RuntimeException mismatch) { throw new DesktopStartupException(LIBRARY_CONTENT_MISMATCH); }
                    cursor = ((Number) row.get("id")).longValue();
                }
            }
        }
        jdbc.update("UPDATE desktop_library_binding SET library_root=? WHERE id=1", selected);
    }
    private record Binding(int version, String installation, String library, String keyCheck) { }
}
