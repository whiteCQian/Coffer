ALTER TABLE file_write_intent ADD COLUMN retention_until DATETIME(6) NULL;
ALTER TABLE file_write_intent ADD COLUMN discarded_at DATETIME(6) NULL;
