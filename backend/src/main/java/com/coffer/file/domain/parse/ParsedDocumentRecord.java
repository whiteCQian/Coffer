package com.coffer.file.domain.parse;

import com.coffer.auth.domain.TenantOwnedEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** Durable parse outcome for one formal file revision, including explicit failures. */
@Entity @Table(name = "parsed_document", uniqueConstraints =
        @UniqueConstraint(name = "uq_parsed_document_version", columnNames = {"owner_id", "file_id", "revision"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ParsedDocumentRecord extends TenantOwnedEntity {
    @Id @Column(length = 36) private String id;
    @Column(name = "file_id", nullable = false) private Long fileId;
    @Column(nullable = false) private Long revision;
    @Column(nullable = false, length = 64) private String contentSha256;
    @Column(nullable = false, length = 16) private String format;
    @Column(name = "parser_version", nullable = false, length = 64) private String parserVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private ParseStatus status;
    @Column(name = "error_code", length = 40) private String errorCode;
    @Column(name = "parsed_at", nullable = false) private LocalDateTime parsedAt;
}
