CREATE TABLE archive_operation_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL,
    preview_id VARCHAR(64),
    operation_source VARCHAR(32) NOT NULL,
    run_mode VARCHAR(16) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    rollback_status VARCHAR(32) NOT NULL,
    total_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    conflicted_count INT NOT NULL DEFAULT 0,
    skipped_count INT NOT NULL DEFAULT 0,
    failure_summary TEXT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    started_at DATETIME(6),
    finished_at DATETIME(6),
    rollback_started_at DATETIME(6),
    rollback_finished_at DATETIME(6),
    CONSTRAINT uk_archive_operation_batch_batch_id UNIQUE (batch_id),
    CONSTRAINT uk_archive_operation_batch_request_id UNIQUE (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_archive_operation_batch_status_created
    ON archive_operation_batch(status, created_at);
CREATE INDEX idx_archive_operation_batch_rollback_created
    ON archive_operation_batch(rollback_status, created_at);

CREATE TABLE archive_operation_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL,
    file_id BIGINT NOT NULL,
    item_key VARCHAR(128) NOT NULL,
    expected_revision BIGINT NOT NULL DEFAULT 0,
    source_file_name TEXT,
    target_file_name TEXT,
    source_category VARCHAR(30),
    target_category VARCHAR(30),
    source_path VARCHAR(500),
    target_path VARCHAR(500),
    source_etag VARCHAR(255),
    source_size BIGINT,
    target_etag VARCHAR(255),
    target_size BIGINT,
    pre_execute_revision BIGINT,
    post_execute_revision BIGINT,
    execution_status VARCHAR(32) NOT NULL,
    execution_step VARCHAR(32) NOT NULL,
    rollback_status VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6),
    failure_code VARCHAR(64),
    failure_message TEXT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    started_at DATETIME(6),
    finished_at DATETIME(6),
    rollback_started_at DATETIME(6),
    rollback_finished_at DATETIME(6),
    CONSTRAINT uk_archive_operation_item_item_key UNIQUE (item_key),
    CONSTRAINT uk_archive_operation_item_batch_file UNIQUE (batch_id, file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_archive_operation_item_batch_status
    ON archive_operation_item(batch_id, execution_status);
CREATE INDEX idx_archive_operation_item_file_created
    ON archive_operation_item(file_id, created_at);
CREATE INDEX idx_archive_operation_item_rollback_status
    ON archive_operation_item(rollback_status, next_attempt_at);
