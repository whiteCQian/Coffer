ALTER TABLE storage_deletion_task ADD COLUMN retention_until DATETIME;
ALTER TABLE storage_deletion_task ADD COLUMN deleted_at DATETIME;
