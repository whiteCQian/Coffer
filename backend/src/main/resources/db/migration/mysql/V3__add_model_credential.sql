CREATE TABLE model_credential (
    provider VARCHAR(32) NOT NULL PRIMARY KEY,
    encrypted_api_key TEXT NOT NULL,
    updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
