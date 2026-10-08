package com.coffer.operations;

import com.coffer.auth.service.AdminAuthorization;
import com.coffer.service.SecretCryptoService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

/** Internal cross-owner maintenance returns only totals. All rewrites commit together or roll back. */
@Service
public class MasterKeyRotationService {
    private final JdbcTemplate jdbc;
    private final SecretCryptoService crypto;
    private final AdminAuthorization authorization;
    public MasterKeyRotationService(JdbcTemplate jdbc, SecretCryptoService crypto, AdminAuthorization authorization) {
        this.jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(5); this.crypto = crypto; this.authorization = authorization;
    }
    private record Source(String table, List<String> keys, String encrypted) { }
    private static final List<Source> SOURCES = List.of(
            new Source("user_model_credential", List.of("owner_id", "provider"), "encrypted_api_key"),
            new Source("model_credential", List.of("provider"), "encrypted_api_key"),
            new Source("model_runtime_endpoint", List.of("id"), "encrypted_api_key"),
            new Source("model_execution_snapshot", List.of("id"), "encrypted_configuration"));
    public record Status(String keyId, boolean previousKeyConfigured, long remaining, long verified, long rewritten) { }
    public Status status() { authorization.requireAdmin(); return new Status(crypto.currentKeyId(), crypto.hasPreviousKey(), remaining(), 0, 0); }
    @Transactional(timeout = 110)
    public Status rotate() {
        authorization.requireAdmin();
        jdbc.queryForObject("SELECT id FROM secret_rotation_lock WHERE id=1 FOR UPDATE", Integer.class);
        long verified = 0, rewritten = 0;
        for (Source source : SOURCES) {
            int offset = 0;
            for (;;) {
                String keys = String.join(",", source.keys());
                var rows = jdbc.queryForList("SELECT " + keys + "," + source.encrypted() + " AS encrypted FROM "
                        + source.table() + " WHERE " + source.encrypted() + " IS NOT NULL AND " + source.encrypted()
                        + " <> '' ORDER BY " + keys + " LIMIT 200 OFFSET " + offset + " FOR UPDATE");
                if (rows.isEmpty()) break;
                for (var row : rows) {
                    String encrypted = (String) row.get("encrypted");
                    String rewrapped = crypto.rewrap(encrypted); // Also authenticate already-current ciphertext.
                    verified++;
                    if (!crypto.authenticatedWithCurrentKey(encrypted)) {
                        List<Object> values = new ArrayList<>(); values.add(rewrapped);
                        source.keys().forEach(key -> values.add(row.get(key))); values.add(encrypted);
                        String predicate = source.keys().stream().map(key -> key + "=?").collect(Collectors.joining(" AND "));
                        int changed = jdbc.update("UPDATE " + source.table() + " SET " + source.encrypted()
                                + "=? WHERE " + predicate + " AND " + source.encrypted() + "=?", values.toArray());
                        if (changed != 1) throw new IllegalStateException("凭据并发变化，请稍后重新轮换");
                        rewritten++;
                    }
                }
                offset += rows.size();
            }
        }
        if (remaining() != 0) throw new IllegalStateException("仍有旧密钥凭据，轮换未完成");
        jdbc.update("INSERT INTO secret_rotation_event(key_id,verified_count,rewritten_count,completed_at) VALUES(?,?,?,CURRENT_TIMESTAMP)",
                crypto.currentKeyId(), verified, rewritten);
        return new Status(crypto.currentKeyId(), crypto.hasPreviousKey(), 0, verified, rewritten);
    }
    private long remaining() {
        long total = 0;
        for (Source source : SOURCES) total += jdbc.queryForObject("SELECT COUNT(*) FROM " + source.table()
                + " WHERE " + source.encrypted() + " IS NOT NULL AND " + source.encrypted() + " <> '' AND "
                + source.encrypted() + " NOT LIKE ?", Long.class, "v1:" + crypto.currentKeyId() + ":%");
        return total;
    }
}
