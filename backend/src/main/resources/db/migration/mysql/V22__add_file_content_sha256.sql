ALTER TABLE file_metadata ADD COLUMN content_sha256 VARCHAR(64) NULL;
ALTER TABLE storage_deletion_task ADD COLUMN content_sha256 VARCHAR(64) NULL;
