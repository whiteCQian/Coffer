CREATE TABLE chat_memory (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 owner_id BIGINT NOT NULL,
 session_id VARCHAR(36) NOT NULL,
 envelope LONGTEXT NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 CONSTRAINT uq_chat_memory_owner_session UNIQUE(owner_id, session_id)
) ENGINE=InnoDB;
CREATE INDEX idx_chat_memory_expiry ON chat_memory(owner_id, expires_at);
