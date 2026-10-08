package com.coffer.file.infrastructure.persistence;

import com.coffer.auth.infrastructure.OwnedRepository;
import com.coffer.file.domain.parse.ParsedDocumentRecord;
import java.util.List;
import java.util.Optional;

public interface ParsedDocumentRecordRepository extends OwnedRepository<ParsedDocumentRecord, String> {
    Optional<ParsedDocumentRecord> findByFileIdAndRevision(Long fileId, Long revision);
    List<ParsedDocumentRecord> findByFileId(Long fileId);
}
