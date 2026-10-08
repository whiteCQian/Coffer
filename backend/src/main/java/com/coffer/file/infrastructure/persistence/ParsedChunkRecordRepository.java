package com.coffer.file.infrastructure.persistence;

import com.coffer.auth.infrastructure.OwnedRepository;
import com.coffer.file.domain.parse.ParsedChunkRecord;
import java.util.List;

public interface ParsedChunkRecordRepository extends OwnedRepository<ParsedChunkRecord, Long> {
    List<ParsedChunkRecord> findByDocumentIdOrderByOrdinalAsc(String documentId);
    void deleteByDocumentId(String documentId);
}
