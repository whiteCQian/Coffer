package com.coffer.privacy;

import com.coffer.auth.service.*;
import com.coffer.repository.*;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.file.storage.StorageConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.*;
import java.util.*;
import java.util.zip.*;

@Service @RequiredArgsConstructor
public class PrivacyService {
    private static final List<String> EXPORT_TABLES = List.of("file_metadata", "tag", "file_tag_mapping",
            "chat_session", "chat_message", "async_task", "governance_preview_batch",
            "governance_preview_item", "archive_operation_batch", "archive_operation_item");
    private final OwnerAuthorization authorization;
    private final ChatSessionRepository sessions;
    private final ChatMessageRepository messages;
    private final MemoryDeletionRepository pending;
    private final FileMetadataRepository files;
    private final FileStoragePort storage;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    @Value("${coffer.privacy.export.max-files:10000}") private int maxExportFiles;
    @Value("${coffer.privacy.export.max-records:50000}") private int maxExportRecords;
    @Value("${coffer.privacy.export.max-uncompressed-bytes:536870912}") private long maxExportBytes;

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
        if (maxExportFiles <= 0 || maxExportRecords <= 0 || maxExportBytes <= 0)
            throw new IllegalStateException("隐私导出资源上限无效");
        if (files.count() > maxExportFiles)
            throw new IllegalArgumentException("文件数量超过单次导出上限");
        try (ZipOutputStream zip = new ZipOutputStream(output, java.nio.charset.StandardCharsets.UTF_8)) {
            var bounded = new BoundedExportOutput(zip, maxExportBytes);
            zip.putNextEntry(new ZipEntry("manifest.json"));
            writeManifest(bounded, owner);
            zip.closeEntry();
            int exportedFiles = 0;
            for (var file : files.findAll()) {
                if (++exportedFiles > maxExportFiles) throw new IllegalArgumentException("文件数量超过单次导出上限");
                if (!owner.equals(authorization.requireOwner()) || !owner.equals(file.getOwnerId()))
                    throw new ResourceNotFoundException();
                var observed = storage.stat(file.getStoragePath());
                if (file.getContentSha256() == null || !file.getContentSha256().matches("[0-9a-f]{64}")
                        || !file.getContentSha256().equals(observed.sha256())
                        || !Objects.equals(file.getFileSize(), observed.size()))
                    throw new StorageConflictException("导出文件正文与记录不一致");
                // IDs avoid traversal, ambiguous filenames and duplicate ZIP members; manifest retains original names.
                zip.putNextEntry(new ZipEntry("files/" + file.getId() + "/original"));
                java.security.MessageDigest digest = sha256();
                long copied;
                try (InputStream source = new java.security.DigestInputStream(
                        storage.readIfUnchanged(file.getStoragePath(), observed), digest)) {
                    copied = source.transferTo(bounded);
                }
                if (copied != observed.size() || !file.getContentSha256().equals(
                        java.util.HexFormat.of().formatHex(digest.digest())))
                    throw new StorageConflictException("导出时文件正文已变化");
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("EXPORT_COMPLETE"));
            bounded.write("Complete".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private void writeManifest(OutputStream output, Long owner) throws IOException {
        try (JsonGenerator generator = json.getFactory().createGenerator(output)) {
            generator.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            generator.writeStartObject();
            generator.writeStringField("format", "coffer-private-export-v1");
            generator.writeStringField("exportedAt", java.time.Instant.now().toString());
            int[] rows = {0};
            // Fixed table names, explicit owner predicate. Credentials and encrypted model snapshots are excluded.
            for (String table : EXPORT_TABLES) {
                generator.writeFieldName(table);
                generator.writeStartArray();
                try {
                    jdbc.query("select * from " + table + " where owner_id = ?",
                            (org.springframework.jdbc.core.PreparedStatementSetter) statement -> statement.setLong(1, owner),
                            (org.springframework.jdbc.core.RowCallbackHandler) result -> {
                                if (++rows[0] > maxExportRecords)
                                    throw new IllegalArgumentException("记录数量超过单次导出上限");
                                var metadata = result.getMetaData();
                                Map<String, Object> row = new LinkedHashMap<>();
                                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                                    Object value = result.getObject(column);
                                    if (value instanceof java.sql.Clob clob) {
                                        long length = clob.length();
                                        if (length > 8L * 1024 * 1024)
                                            throw new IllegalArgumentException("单条导出记录超过大小上限");
                                        value = clob.getSubString(1, Math.toIntExact(length));
                                    }
                                    row.put(metadata.getColumnLabel(column), value);
                                }
                                try { json.writeValue(generator, row); }
                                catch (IOException failure) { throw new UncheckedIOException(failure); }
                            });
                } catch (UncheckedIOException failure) { throw failure.getCause(); }
                generator.writeEndArray();
            }
            generator.writeEndObject();
        }
    }

    private static java.security.MessageDigest sha256() {
        try { return java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class BoundedExportOutput extends FilterOutputStream {
        private final long max;
        private long written;
        private BoundedExportOutput(OutputStream output, long max) { super(output); this.max = max; }
        @Override public void write(int value) throws IOException {
            reserve(1); out.write(value);
        }
        @Override public void write(byte[] value, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, value.length);
            reserve(length); out.write(value, offset, length);
        }
        private void reserve(long size) {
            if (size > max - written) throw new IllegalArgumentException("导出内容超过单次大小上限");
            written += size;
        }
    }
}
