-- Existing installations were baselined at version 3 before V3 was able to
-- create this table. Keep this migration idempotent so both new and existing
-- MySQL databases converge on the schema required by ModelCredential.
CREATE TABLE IF NOT EXISTS model_credential (
    provider VARCHAR(32) NOT NULL PRIMARY KEY,
    encrypted_api_key TEXT NOT NULL,
    updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
