ALTER TABLE work_save_intent ADD COLUMN before_size BIGINT;
ALTER TABLE work_save_intent ADD COLUMN before_modified_time VARCHAR(64);
ALTER TABLE work_save_intent ADD COLUMN before_file_key VARCHAR(255);
ALTER TABLE work_save_intent ADD COLUMN preserve_only BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE desktop_work_copy (
    id VARCHAR(36) PRIMARY KEY, owner_id BIGINT NOT NULL, file_id BIGINT NOT NULL,
    work_key VARCHAR(500) NOT NULL, before_key VARCHAR(500) NOT NULL, file_name TEXT NOT NULL,
    expected_revision BIGINT NOT NULL, before_sha256 VARCHAR(64) NOT NULL, before_size BIGINT NOT NULL,
    before_modified_time VARCHAR(64) NOT NULL, before_file_key VARCHAR(255) NOT NULL,
    status VARCHAR(24) NOT NULL, error_code VARCHAR(64), operation_id VARCHAR(36),
    recovered_file_id BIGINT, confirmed_sha256 VARCHAR(64), created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_desktop_work_key UNIQUE(owner_id, work_key)
);
CREATE INDEX idx_desktop_work_status ON desktop_work_copy(owner_id, status, created_at);
