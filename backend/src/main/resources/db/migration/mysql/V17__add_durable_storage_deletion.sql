CREATE TABLE storage_deletion_task (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    task_key VARCHAR(160) NOT NULL,
    file_id BIGINT NOT NULL,
    object_path VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL,
    next_attempt_at DATETIME(6),
    last_error_code VARCHAR(64),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_storage_deletion_task_key UNIQUE (owner_id, task_key),
    CONSTRAINT fk_storage_deletion_task_owner FOREIGN KEY (owner_id) REFERENCES app_user(id),
    INDEX idx_storage_deletion_due (owner_id, status, next_attempt_at)
) ENGINE=InnoDB;
