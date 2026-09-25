CREATE TABLE model_runtime_endpoint (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_mode VARCHAR(16) NOT NULL,
    capability VARCHAR(16) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    encrypted_api_key TEXT,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_model_runtime_endpoint_mode_capability UNIQUE (run_mode, capability)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
