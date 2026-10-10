package com.coffer.config;

import io.minio.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import com.coffer.web.WebLimits;

/** First activation reconciles all physical versions, including disabled owners and orphan objects.
 * The initialized flag is committed only after a complete inventory. No HTTP maintenance bypass. */
@Component @Profile("prod") @RequiredArgsConstructor
public class MinioQuotaInitializer {
    private final MinioClient minio;
    private final MinioConfig.MinioProperties properties;
    private final JdbcTemplate jdbc;
    @EventListener(ApplicationReadyEvent.class) @Order(100)
    @Transactional
    public void initialize() {
        if (properties.getBucketName() == null || properties.getBucketName().isBlank()) return;
        jdbc.queryForObject("SELECT id FROM web_quota_lock WHERE id=1 FOR UPDATE", Long.class);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT initialized FROM web_quota_lock WHERE id=1", Boolean.class))) return;
        Map<String, Long> bytes = new HashMap<>();
        Map<String, Long> owners = new HashMap<>();
        Set<Long> known = new HashSet<>(jdbc.queryForList("SELECT id FROM app_user WHERE role='USER'", Long.class));
        try {
            int count = 0;
            for (var listed : minio.listObjects(ListObjectsArgs.builder().bucket(properties.getBucketName()).recursive(true).includeVersions(true).build())) {
                if (++count > 1000000) throw new IllegalStateException("Inventory exceeds deployment baseline");
                var item = listed.get();
                String key = item.objectName();
                if (!key.matches("users/[1-9][0-9]*/.+")) throw new IllegalStateException("Unowned storage object requires review");
                long owner = Long.parseLong(key.split("/", 3)[1]);
                if (!known.contains(owner)) throw new IllegalStateException("Unknown storage owner requires review");
                owners.put(key, owner);
                bytes.merge(key, item.isDeleteMarker() ? 0L : item.size(), Math::addExact);
            }
        } catch (Exception failure) { throw new IllegalStateException("MinIO 配额完整清单核对失败；禁止新增写入", failure); }
        jdbc.update("DELETE FROM web_storage_allocation");
        for (var entry : bytes.entrySet())
            jdbc.update("INSERT INTO web_storage_allocation(key_hash,owner_id,object_key,bytes,state,created_at) VALUES(?,?,?,?,'STORED',?)",
                    WebLimits.hash(entry.getKey()), owners.get(entry.getKey()), entry.getKey(), entry.getValue(), Timestamp.from(Instant.EPOCH));
        // Keep failed/prepared intentions charged even when IO outcome is uncertain; never release on age alone.
        for (var row : jdbc.queryForList("SELECT owner_id,object_key,declared_size FROM file_write_intent WHERE owner_id IS NOT NULL AND status NOT IN ('REGISTERED','DISCARDED')")) {
            String key = (String)row.get("object_key");
            if (!bytes.containsKey(key))
                jdbc.update("INSERT INTO web_storage_allocation(key_hash,owner_id,object_key,bytes,state,created_at) VALUES(?,?,?,?,'RESERVED',?)",
                        WebLimits.hash(key), row.get("owner_id"), key, row.get("declared_size"), Timestamp.from(Instant.EPOCH));
        }
        jdbc.update("UPDATE web_quota_lock SET initialized=TRUE WHERE id=1");
    }
}
