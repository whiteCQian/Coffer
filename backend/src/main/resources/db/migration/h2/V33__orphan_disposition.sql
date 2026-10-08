ALTER TABLE file_write_intent ADD COLUMN retention_until TIMESTAMP;
ALTER TABLE file_write_intent ADD COLUMN discarded_at TIMESTAMP;
