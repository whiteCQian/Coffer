CREATE TABLE governance_compensation_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_key VARCHAR(160) NOT NULL,
    batch_id VARCHAR(64) NOT NULL,
    item_id BIGINT NOT NULL,
    action VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    object_path VARCHAR(500),
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6),
    last_error TEXT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6),
    CONSTRAINT uk_governance_compensation_task_key UNIQUE (task_key),
    INDEX idx_governance_compensation_due (status, next_attempt_at),
    INDEX idx_governance_compensation_batch (batch_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
