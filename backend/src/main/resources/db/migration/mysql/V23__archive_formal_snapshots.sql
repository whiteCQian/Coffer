ALTER TABLE archive_operation_item
    ADD COLUMN snapshot_version INT NULL,
    ADD COLUMN source_snapshot_json TEXT NULL,
    ADD COLUMN target_snapshot_json TEXT NULL,
    ADD COLUMN source_sha256 VARCHAR(64) NULL,
    ADD COLUMN target_sha256 VARCHAR(64) NULL,
    ADD COLUMN rollback_result_revision BIGINT NULL;

-- Existing ledger rows deliberately keep NULL snapshot_version: their history is incomplete.
