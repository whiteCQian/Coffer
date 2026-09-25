CREATE TABLE IF NOT EXISTS vector_cleanup_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NULL,
    last_error VARCHAR(10000) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_vector_cleanup_due (status, next_attempt_at)
);
CREATE TABLE IF NOT EXISTS vector_reindex_job (
    job_id VARCHAR(64) PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    total_count INT NOT NULL DEFAULT 0,
    processed_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    skipped_count INT NOT NULL DEFAULT 0,
    error_summary VARCHAR(10000) NULL,
    started_at DATETIME NULL,
    finished_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);
