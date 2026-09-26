CREATE TABLE memory_deletion (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    CONSTRAINT fk_memory_deletion_owner FOREIGN KEY (owner_id) REFERENCES app_user(id),
    INDEX idx_memory_deletion_owner(owner_id)
);
