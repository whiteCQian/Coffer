package com.coffer.privacy;

import com.coffer.auth.service.*;
import com.coffer.repository.*;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.service.MinioStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.*;
import java.util.*;
import java.util.zip.*;

@Service @RequiredArgsConstructor
public class PrivacyService {
    private final OwnerAuthorization authorization;
    private final ChatSessionRepository sessions;
    private final ChatMessageRepository messages;
    private final MemoryDeletionRepository pending;
    private final FileMetadataRepository files;
    private final MinioStorageService storage;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    @OwnerOnly public Map<String, Long> status() {
        Long owner = authorization.requireOwner();
        return Map.of("files", files.count(), "conversations", sessions.count(), "pendingMemories", pending.count(),
                "pendingObjects", jdbc.queryForObject("select count(*) from storage_deletion_task where owner_id = ? and status <> 'SUCCEEDED'", Long.class, owner),
                "pendingVectors", jdbc.queryForObject("select count(*) from vector_cleanup_task where owner_id = ? and status <> 'COMPLETED'", Long.class, owner));
    }

    /** Caller holds the erase gate until this transaction has committed. */
    @Transactional
    public void eraseConversations(String confirmation) {
        authorization.requireOwner();
        if (!"DELETE_MY_CONVERSATIONS".equals(confirmation)) throw new IllegalArgumentException("请明确确认删除全部对话");
        for (var session : sessions.findAll()) pending.save(new MemoryDeletion(session.getSessionId()));
        messages.deleteAllInBatch();
        sessions.deleteAllInBatch();
    }

    @OwnerOnly
    public void export(OutputStream output) throws IOException {
        Long owner = authorization.requireOwner();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", "coffer-private-export-v1");
        manifest.put("exportedAt", java.time.Instant.now().toString());
        // Fixed table names, explicit owner predicate. Credentials and encrypted model snapshots are not exported.
        for (String table : List.of("file_metadata", "tag", "file_tag_mapping", "chat_session", "chat_message",
                "async_task", "governance_preview_batch", "governance_preview_item", "archive_operation_batch", "archive_operation_item")) {
            var rows = jdbc.queryForList("select * from " + table + " where owner_id = ?", owner);
            for (var row : rows) for (var entry : row.entrySet()) {
                if (entry.getValue() instanceof java.sql.Clob clob) {
                    try { entry.setValue(clob.getSubString(1, Math.toIntExact(clob.length()))); }
                    catch (java.sql.SQLException failure) { throw new IOException("导出数据读取失败"); }
                }
            }
            manifest.put(table, rows);
        }
        var exportedFiles = files.findAll();
        try (ZipOutputStream zip = new ZipOutputStream(output, java.nio.charset.StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(json.writeValueAsBytes(manifest)); zip.closeEntry();
            for (var file : exportedFiles) {
                authorization.requireOwner();
                // IDs avoid traversal, ambiguous filenames and duplicate ZIP members; manifest retains original names.
                zip.putNextEntry(new ZipEntry("files/" + file.getId() + "/original"));
                try (InputStream source = storage.getFileStream(null, file.getStoragePath())) { source.transferTo(zip); }
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("EXPORT_COMPLETE")); zip.write("Complete".getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
        }
    }
}
