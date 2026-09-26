CREATE TABLE chat_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    session_id VARCHAR(36) NOT NULL UNIQUE,
    CONSTRAINT fk_chat_session_owner FOREIGN KEY (owner_id) REFERENCES app_user(id)
);
CREATE INDEX idx_chat_session_owner ON chat_session(owner_id, session_id);
-- Legacy vector document IDs did not bind a file revision. Rebuild them in each owner's namespace.
UPDATE file_metadata SET vector_indexed_at = NULL, vector_index_generation = NULL;
