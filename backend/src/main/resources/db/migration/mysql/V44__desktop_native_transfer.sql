CREATE TABLE desktop_native_transfer (
 id VARCHAR(36) PRIMARY KEY, owner_id BIGINT NOT NULL,
 inbox_key VARCHAR(1024) NOT NULL, source_path VARCHAR(4096) NOT NULL,
 claimed_path VARCHAR(4096) NOT NULL, source_size BIGINT NOT NULL,
 modified_millis BIGINT NOT NULL, file_key VARCHAR(1024) NOT NULL,
 sha256 CHAR(64) NOT NULL, state VARCHAR(16) NOT NULL,
 last_error VARCHAR(512), created_at TIMESTAMP NOT NULL,
 CONSTRAINT fk_native_transfer_owner FOREIGN KEY(owner_id) REFERENCES app_user(id)
);
CREATE INDEX idx_native_transfer_owner_state ON desktop_native_transfer(owner_id,state);
