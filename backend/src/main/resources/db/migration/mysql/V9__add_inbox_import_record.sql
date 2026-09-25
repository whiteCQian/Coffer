CREATE TABLE inbox_import_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    snapshot_key VARCHAR(64) NOT NULL,
    source_path VARCHAR(1000) NOT NULL,
    source_file_name VARCHAR(255) NOT NULL,
    source_size BIGINT NOT NULL,
    source_modified_at DATETIME(6) NOT NULL,
    content_sha256 VARCHAR(64),
    content_type VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    stable_observations INT NOT NULL DEFAULT 1,
    attempt_count INT NOT NULL DEFAULT 0,
    task_id VARCHAR(64),
    file_id BIGINT,
    first_seen_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    stable_since_at DATETIME(6),
    import_started_at DATETIME(6),
    import_finished_at DATETIME(6),
    next_attempt_at DATETIME(6),
    last_error TEXT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_inbox_import_record_snapshot_key UNIQUE (snapshot_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_inbox_import_record_status_seen
    ON inbox_import_record(status, last_seen_at);
CREATE INDEX idx_inbox_import_record_content_sha256
    ON inbox_import_record(content_sha256);
CREATE INDEX idx_inbox_import_record_source_path
    ON inbox_import_record(source_path(191));
