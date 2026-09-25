CREATE TABLE async_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    file_name VARCHAR(255),
    status VARCHAR(20) NOT NULL,
    progress INT,
    result TEXT,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    CONSTRAINT uk_async_task_task_id UNIQUE (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE chat_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    user_message TEXT,
    ai_response TEXT,
    `timestamp` DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE file_metadata (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_name TEXT NOT NULL,
    file_size BIGINT NOT NULL,
    file_type VARCHAR(50),
    storage_path VARCHAR(500),
    upload_time DATETIME(6),
    summary TEXT,
    status VARCHAR(20) NOT NULL,
    task_id VARCHAR(64),
    category VARCHAR(30),
    archived BOOLEAN NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tag (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tag_name VARCHAR(255) NOT NULL,
    category VARCHAR(50),
    created_at DATETIME(6),
    CONSTRAINT uk_tag_tag_name UNIQUE (tag_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE file_tag_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    confirmation_status VARCHAR(20) NOT NULL,
    confirmed_at DATETIME(6),
    confirmation_note VARCHAR(500),
    CONSTRAINT uk_file_tag_mapping_file_tag UNIQUE (file_id, tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE model_call_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(128),
    user_message TEXT,
    ai_response TEXT,
    prompt_tokens INT,
    completion_tokens INT,
    total_tokens INT,
    retry_count INT,
    response_time_ms BIGINT,
    call_time DATETIME(6) NOT NULL,
    model_name VARCHAR(128),
    status VARCHAR(20),
    error_message TEXT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
