ALTER TABLE async_task ADD run_mode VARCHAR(16) NOT NULL DEFAULT 'API';

CREATE TABLE model_runtime_setting (
    id BIGINT PRIMARY KEY,
    active_mode VARCHAR(16) NOT NULL,
    api_validated_at TIMESTAMP,
    local_validated_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL
);

INSERT INTO model_runtime_setting (id, active_mode, updated_at)
VALUES (1, 'API', CURRENT_TIMESTAMP);
