ALTER TABLE model_runtime_endpoint
    ADD COLUMN use_chat_credentials BOOLEAN NOT NULL DEFAULT FALSE;
