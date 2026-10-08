CREATE TABLE parsed_document (
    id VARCHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    format VARCHAR(16) NOT NULL,
    parser_version VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    error_code VARCHAR(40),
    parsed_at DATETIME NOT NULL,
    CONSTRAINT fk_parsed_document_owner FOREIGN KEY(owner_id) REFERENCES app_user(id),
    CONSTRAINT uq_parsed_document_version UNIQUE(owner_id, file_id, revision)
);
CREATE TABLE parsed_chunk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    document_id VARCHAR(36) NOT NULL,
    ordinal INT NOT NULL,
    original_text TEXT NOT NULL,
    source_kind VARCHAR(24) NOT NULL,
    source_start INT NOT NULL,
    source_end INT NOT NULL,
    start_character INT NOT NULL,
    end_character INT NOT NULL,
    CONSTRAINT fk_parsed_chunk_owner FOREIGN KEY(owner_id) REFERENCES app_user(id),
    CONSTRAINT fk_parsed_chunk_document FOREIGN KEY(document_id) REFERENCES parsed_document(id),
    CONSTRAINT uq_parsed_chunk_ordinal UNIQUE(owner_id, document_id, ordinal)
);
