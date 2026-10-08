ALTER TABLE archive_operation_item ADD COLUMN snapshot_version INT;
ALTER TABLE archive_operation_item ADD COLUMN source_snapshot_json TEXT;
ALTER TABLE archive_operation_item ADD COLUMN target_snapshot_json TEXT;
ALTER TABLE archive_operation_item ADD COLUMN source_sha256 VARCHAR(64);
ALTER TABLE archive_operation_item ADD COLUMN target_sha256 VARCHAR(64);
ALTER TABLE archive_operation_item ADD COLUMN rollback_result_revision BIGINT;

-- Existing ledger rows deliberately keep NULL snapshot_version: their history is incomplete.
