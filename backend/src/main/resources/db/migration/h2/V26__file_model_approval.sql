CREATE TABLE file_model_approval (
    id VARCHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    file_revision BIGINT NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    configuration_version VARCHAR(64) NOT NULL,
    capability VARCHAR(16) NOT NULL,
    endpoint_url VARCHAR(1000) NOT NULL,
    model_name VARCHAR(255) NOT NULL,
    risk VARCHAR(16) NOT NULL,
    confirmed_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_file_model_approval_owner FOREIGN KEY(owner_id) REFERENCES app_user(id)
);
CREATE INDEX idx_file_model_approval_lookup ON file_model_approval(owner_id, file_id, file_revision, capability);
