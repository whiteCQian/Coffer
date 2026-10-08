package com.coffer.file.application.parse;

import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.parse.*;
import com.coffer.file.infrastructure.persistence.ParsedChunkRecordRepository;
import com.coffer.file.infrastructure.persistence.ParsedDocumentRecordRepository;
import com.coffer.file.storage.StorageConflictException;
import com.coffer.file.storage.FileStoragePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Persists source-addressable chunks and explicit parse failures under file identity. */
@Service @RequiredArgsConstructor @com.coffer.auth.service.OwnerOnly
public class ParsedDocumentStore {
    private static final int MAX_CHUNK_CHARS = 30_000;
    private final ParsedDocumentRecordRepository documents;
    private final ParsedChunkRecordRepository chunks;
    private final FileStoragePort storage;

    @Transactional
    public void save(FileMetadata file, ParsedDocument parsed) {
        if (file.getId() == null || file.getRevision() == null || file.getContentSha256() == null
                || !Objects.equals(file.getId(), parsed.fileId())
                || file.getRevision() != parsed.revision())
            throw new StorageConflictException("解析结果与文件身份不一致");
        var observed = com.coffer.file.application.VerifiedFileSource.statBounded(storage, file.getStoragePath());
        if (!Objects.equals(file.getContentSha256(), observed.sha256())
                || !Objects.equals(file.getFileSize(), observed.size()))
            throw new StorageConflictException("解析来源对象已变化");
        ParsedDocumentRecord row = documents.findByFileIdAndRevision(file.getId(), file.getRevision())
                .orElseGet(() -> ParsedDocumentRecord.builder().id(UUID.randomUUID().toString())
                        .fileId(file.getId()).revision(file.getRevision()).build());
        if (row.getContentSha256() != null && !row.getContentSha256().equals(file.getContentSha256()))
            throw new StorageConflictException("同一文件版本的内容摘要已变化");
        chunks.deleteByDocumentId(row.getId());
        chunks.flush();
        row.setContentSha256(file.getContentSha256());
        row.setFormat(parsed.format());
        row.setParserVersion(parsed.parserVersion());
        row.setStatus(parsed.status());
        row.setErrorCode(parsed.errorCode());
        row.setParsedAt(LocalDateTime.now());
        documents.saveAndFlush(row);
        int ordinal = 0;
        for (ParsedDocument.Chunk chunk : parsed.chunks()) {
            String value = chunk.text();
            if (value.isEmpty()) {
                chunks.save(toRow(row.getId(), ordinal++, chunk, "", chunk.startCharacter(), chunk.endCharacter()));
                continue;
            }
            for (int start = 0; start < value.length();) {
                int end = Math.min(value.length(), start + MAX_CHUNK_CHARS);
                if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
                chunks.save(toRow(row.getId(), ordinal++, chunk, value.substring(start, end),
                        chunk.startCharacter() + start, chunk.startCharacter() + end - 1));
                start = end;
            }
        }
    }

    @Transactional(readOnly = true)
    public ParsedDocument load(FileMetadata file) {
        ParsedDocumentRecord row = documents.findByFileIdAndRevision(file.getId(), file.getRevision())
                .orElse(null);
        if (row == null || !Objects.equals(row.getContentSha256(), file.getContentSha256())) return null;
        List<ParsedDocument.Chunk> result = chunks.findByDocumentIdOrderByOrdinalAsc(row.getId()).stream()
                .map(c -> new ParsedDocument.Chunk(c.getOriginalText(), c.getSourceKind(),
                        c.getSourceStart(), c.getSourceEnd(), c.getStartCharacter(), c.getEndCharacter()))
                .toList();
        return new ParsedDocument(file.getId(), file.getRevision(), row.getFormat(), row.getParserVersion(),
                row.getStatus(), row.getErrorCode(), result);
    }

    @Transactional
    public void deleteFile(Long fileId) {
        for (ParsedDocumentRecord document : documents.findByFileId(fileId)) {
            chunks.deleteByDocumentId(document.getId());
            chunks.flush();
            documents.delete(document);
        }
    }

    private static ParsedChunkRecord toRow(String documentId, int ordinal, ParsedDocument.Chunk chunk,
                                           String text, int startCharacter, int endCharacter) {
        return ParsedChunkRecord.builder().documentId(documentId).ordinal(ordinal).originalText(text)
                .sourceKind(chunk.sourceKind()).sourceStart(chunk.start()).sourceEnd(chunk.end())
                .startCharacter(startCharacter).endCharacter(endCharacter).build();
    }
}
