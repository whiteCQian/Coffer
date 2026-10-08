ALTER TABLE file_rename_intent ADD COLUMN attempts INT NOT NULL DEFAULT 0;
ALTER TABLE file_rename_intent ADD COLUMN next_attempt_at TIMESTAMP;
UPDATE file_rename_intent SET next_attempt_at = created_at WHERE status = 'PREPARED';
CREATE INDEX idx_file_rename_due ON file_rename_intent(owner_id, status, next_attempt_at);
