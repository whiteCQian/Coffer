CREATE TABLE file_rename_intent (
    id VARCHAR(64) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    expected_revision BIGINT NOT NULL,
    before_name TEXT NOT NULL,
    after_name TEXT NOT NULL,
    status VARCHAR(24) NOT NULL,
    error_code VARCHAR(40),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT fk_file_rename_owner FOREIGN KEY(owner_id) REFERENCES app_user(id),
    INDEX idx_file_rename_recovery(owner_id, status, created_at)
);
