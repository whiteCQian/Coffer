ALTER TABLE storage_deletion_task ADD COLUMN retention_until TIMESTAMP;
ALTER TABLE storage_deletion_task ADD COLUMN deleted_at TIMESTAMP;
