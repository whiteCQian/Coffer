CREATE TABLE model_execution_snapshot (
    id VARCHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    configuration_version VARCHAR(64) NOT NULL,
    run_mode VARCHAR(16) NOT NULL,
    encrypted_configuration TEXT NOT NULL,
    confirmed_at TIMESTAMP NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    CONSTRAINT fk_model_snapshot_owner FOREIGN KEY(owner_id) REFERENCES app_user(id)
);
CREATE INDEX idx_model_snapshot_owner ON model_execution_snapshot(owner_id, confirmed_at);
ALTER TABLE async_task ADD COLUMN model_snapshot_id VARCHAR(36);
ALTER TABLE file_metadata ADD COLUMN model_snapshot_id VARCHAR(36);
ALTER TABLE governance_preview_batch ADD COLUMN model_snapshot_id VARCHAR(36);
ALTER TABLE vector_reindex_job ADD COLUMN model_snapshot_id VARCHAR(36);
ALTER TABLE chat_message ADD COLUMN model_snapshot_id VARCHAR(36);
ALTER TABLE user_model_runtime_setting ADD COLUMN inbox_snapshot_id VARCHAR(36);
UPDATE model_call_log SET user_message=NULL, ai_response=NULL, session_id=NULL, model_name=NULL, error_message=NULL;
