CREATE TABLE model_credential (
    provider VARCHAR(32) NOT NULL PRIMARY KEY,
    encrypted_api_key TEXT NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
