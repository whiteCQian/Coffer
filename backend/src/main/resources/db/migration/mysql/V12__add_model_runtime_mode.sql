ALTER TABLE async_task ADD COLUMN run_mode VARCHAR(16) NOT NULL DEFAULT 'API';

CREATE TABLE model_runtime_setting (
    id BIGINT PRIMARY KEY,
    active_mode VARCHAR(16) NOT NULL,
    api_validated_at DATETIME(6),
    local_validated_at DATETIME(6),
    updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO model_runtime_setting (id, active_mode, updated_at)
VALUES (1, 'API', CURRENT_TIMESTAMP(6));
