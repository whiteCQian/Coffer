CREATE TABLE file_rename_intent (
    id VARCHAR(64) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    expected_revision BIGINT NOT NULL,
    before_name TEXT NOT NULL,
    after_name TEXT NOT NULL,
    status VARCHAR(24) NOT NULL,
    error_code VARCHAR(40),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_file_rename_owner FOREIGN KEY(owner_id) REFERENCES app_user(id)
);
CREATE INDEX idx_file_rename_recovery ON file_rename_intent(owner_id, status, created_at);
