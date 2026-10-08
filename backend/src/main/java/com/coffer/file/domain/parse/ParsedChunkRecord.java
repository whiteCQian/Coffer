package com.coffer.file.domain.parse;

import com.coffer.auth.domain.TenantOwnedEntity;
import jakarta.persistence.*;
import lombok.*;

/** A bounded excerpt whose location points to the original document. */
@Entity @Table(name = "parsed_chunk", uniqueConstraints =
        @UniqueConstraint(name = "uq_parsed_chunk_ordinal", columnNames = {"owner_id", "document_id", "ordinal"}))
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class ParsedChunkRecord extends TenantOwnedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "document_id", nullable = false, length = 36) private String documentId;
    @Column(nullable = false) private Integer ordinal;
    @Column(name = "original_text", nullable = false, columnDefinition = "TEXT") private String originalText;
    @Column(name = "source_kind", nullable = false, length = 24) private String sourceKind;
    @Column(name = "source_start", nullable = false) private Integer sourceStart;
    @Column(name = "source_end", nullable = false) private Integer sourceEnd;
    @Column(name = "start_character", nullable = false) private Integer startCharacter;
    @Column(name = "end_character", nullable = false) private Integer endCharacter;
}
