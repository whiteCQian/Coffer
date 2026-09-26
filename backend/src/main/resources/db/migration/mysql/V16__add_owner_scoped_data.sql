ALTER TABLE chat_message ADD COLUMN owner_id BIGINT NULL, ADD CONSTRAINT fk_chat_message_owner FOREIGN KEY (owner_id) REFERENCES app_user(id);
CREATE INDEX idx_chat_message_owner ON chat_message(owner_id);
ALTER TABLE model_call_log ADD COLUMN owner_id BIGINT NULL, ADD CONSTRAINT fk_model_call_log_owner FOREIGN KEY (owner_id) REFERENCES app_user(id);
CREATE INDEX idx_model_call_log_owner ON model_call_log(owner_id);
ALTER TABLE vector_cleanup_task ADD COLUMN owner_id BIGINT NULL, ADD CONSTRAINT fk_vector_cleanup_task_owner FOREIGN KEY (owner_id) REFERENCES app_user(id);
CREATE INDEX idx_vector_cleanup_task_owner ON vector_cleanup_task(owner_id);
ALTER TABLE vector_reindex_job ADD COLUMN owner_id BIGINT NULL, ADD CONSTRAINT fk_vector_reindex_job_owner FOREIGN KEY (owner_id) REFERENCES app_user(id);
CREATE INDEX idx_vector_reindex_job_owner ON vector_reindex_job(owner_id);
ALTER TABLE file_metadata ADD COLUMN owner_id BIGINT NULL, ADD CONSTRAINT fk_file_metadata_owner FOREIGN KEY (owner_id) REFERENCES app_user(id);
CREATE INDEX idx_file_metadata_owner ON file_metadata(owner_id);

ALTER TABLE archive_object_name_reservation ADD COLUMN owner_id BIGINT NULL;
ALTER TABLE archive_object_name_reservation DROP INDEX uk_archive_object_name_reservation;
ALTER TABLE archive_object_name_reservation ADD CONSTRAINT uk_archive_object_name_reservation
    UNIQUE (owner_id, business_date, category_slug, normalized_file_name, sequence_number);
ALTER TABLE archive_object_name_reservation ADD CONSTRAINT fk_archive_object_name_reservation_owner FOREIGN KEY (owner_id) REFERENCES app_user(id);
CREATE INDEX idx_archive_object_name_reservation_owner ON archive_object_name_reservation(owner_id);

ALTER TABLE archive_operation_batch ADD COLUMN owner_id BIGINT NULL;
ALTER TABLE archive_operation_batch DROP INDEX uk_archive_operation_batch_batch_id;
ALTER TABLE archive_operation_batch ADD CONSTRAINT uk_archive_operation_batch_batch_id UNIQUE (owner_id, batch_id);
ALTER TABLE archive_operation_batch DROP INDEX uk_archive_operation_batch_request_id;
ALTER TABLE archive_operation_batch ADD CONSTRAINT uk_archive_operation_batch_request_id UNIQUE (owner_id, request_id);
ALTER TABLE archive_operation_batch ADD CONSTRAINT fk_archive_operation_batch_owner FOREIGN KEY (owner_id) REFERENCES app_user(id);
CREATE INDEX idx_archive_operation_batch_owner ON archive_operation_batch(owner_id);
ALTER TABLE archive_operation_item ADD COLUMN owner_id BIGINT NULL;
ALTER TABLE archive_operation_item DROP INDEX uk_archive_operation_item_item_key;
ALTER TABLE archive_operation_item ADD CONSTRAINT uk_archive_operation_item_item_key UNIQUE (owner_id, item_key);
ALTER TABLE archive_operation_item DROP INDEX uk_archive_operation_item_batch_file;
ALTER TABLE archive_operation_item ADD CONSTRAINT uk_archive_operation_item_batch_file UNIQUE (owner_id, batch_id, file_id);
ALTER TABLE archive_operation_item ADD CONSTRAINT fk_archive_operation_item_owner FOREIGN KEY (owner_id) REFERENCES app_user(id);
CREATE INDEX idx_archive_operation_item_owner ON archive_operation_item(owner_id);
ALTER TABLE governance_compensation_task ADD COLUMN owner_id BIGINT NULL, ADD CONSTRAINT fk_governance_compensation_task_owner FOREIGN KEY (owner_id) REFERENCES app_user(id);
CREATE INDEX idx_governance_compensation_task_owner ON governance_compensation_task(owner_id);
ALTER TABLE governance_preview_batch ADD COLUMN owner_id BIGINT NULL;
ALTER TABLE governance_preview_batch DROP INDEX uk_governance_preview_batch_preview_id;
ALTER TABLE governance_preview_batch ADD CONSTRAINT uk_governance_preview_batch_preview_id UNIQUE (owner_id, preview_id);
ALTER TABLE governance_preview_batch DROP INDEX uk_governance_preview_batch_request_id;
ALTER TABLE governance_preview_batch ADD CONSTRAINT uk_governance_preview_batch_request_id UNIQUE (owner_id, request_id);
ALTER TABLE governance_preview_batch ADD CONSTRAINT fk_governance_preview_batch_owner FOREIGN KEY (owner_id) REFERENCES app_user(id);
CREATE INDEX idx_governance_preview_batch_owner ON governance_preview_batch(owner_id);
ALTER TABLE governance_preview_item ADD COLUMN owner_id BIGINT NULL;
ALTER TABLE governance_preview_item DROP INDEX uk_governance_preview_item_preview_file;
ALTER TABLE governance_preview_item ADD CONSTRAINT uk_governance_preview_item_preview_file UNIQUE (owner_id, preview_id, file_id);
ALTER TABLE governance_preview_item ADD CONSTRAINT fk_governance_preview_item_owner FOREIGN KEY (owner_id) REFERENCES app_user(id);
CREATE INDEX idx_governance_preview_item_owner ON governance_preview_item(owner_id);
ALTER TABLE inbox_import_record ADD COLUMN owner_id BIGINT NULL;
ALTER TABLE inbox_import_record DROP INDEX uk_inbox_import_record_snapshot_key;
ALTER TABLE inbox_import_record ADD CONSTRAINT uk_inbox_import_record_snapshot_key UNIQUE (owner_id, snapshot_key);
ALTER TABLE inbox_import_record ADD CONSTRAINT fk_inbox_import_record_owner FOREIGN KEY (owner_id) REFERENCES app_user(id);
CREATE INDEX idx_inbox_import_record_owner ON inbox_import_record(owner_id);
ALTER TABLE file_tag_mapping ADD COLUMN owner_id BIGINT NULL;
ALTER TABLE file_tag_mapping DROP INDEX uk_file_tag_mapping_file_tag;
ALTER TABLE file_tag_mapping ADD CONSTRAINT uk_file_tag_mapping_file_tag UNIQUE (owner_id, file_id, tag_id);
ALTER TABLE file_tag_mapping ADD CONSTRAINT fk_file_tag_mapping_owner FOREIGN KEY (owner_id) REFERENCES app_user(id);
CREATE INDEX idx_file_tag_mapping_owner ON file_tag_mapping(owner_id);

ALTER TABLE tag ADD COLUMN owner_id BIGINT NULL;
-- Legacy installs have both explicitly named indexes and Hibernate-generated
-- names. Match the unique key by its columns so the owner-scoped replacement
-- also works when the physical index name differs.
SET @tag_unique_drop_list = (
    SELECT GROUP_CONCAT(CONCAT('DROP INDEX `', REPLACE(index_name, '`', '``'), '`') SEPARATOR ', ')
    FROM (
        SELECT index_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'tag' AND non_unique = 0
        GROUP BY index_name
        HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') = 'tag_name'
    ) AS tag_unique_indexes
);
SET @tag_unique_drop_sql = IF(@tag_unique_drop_list IS NULL, 'SELECT 1',
    CONCAT('ALTER TABLE `tag` ', @tag_unique_drop_list));
PREPARE tag_unique_drop_stmt FROM @tag_unique_drop_sql;
EXECUTE tag_unique_drop_stmt;
DEALLOCATE PREPARE tag_unique_drop_stmt;
ALTER TABLE tag ADD CONSTRAINT uk_tag_owner_name UNIQUE (owner_id, tag_name);
ALTER TABLE tag ADD CONSTRAINT fk_tag_owner FOREIGN KEY (owner_id) REFERENCES app_user(id);
CREATE INDEX idx_tag_owner ON tag(owner_id);

ALTER TABLE async_task ADD COLUMN owner_id BIGINT NULL;
SET @async_task_unique_drop_list = (
    SELECT GROUP_CONCAT(CONCAT('DROP INDEX `', REPLACE(index_name, '`', '``'), '`') SEPARATOR ', ')
    FROM (
        SELECT index_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'async_task' AND non_unique = 0
        GROUP BY index_name
        HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') = 'task_id'
    ) AS async_task_unique_indexes
);
SET @async_task_unique_drop_sql = IF(@async_task_unique_drop_list IS NULL, 'SELECT 1',
    CONCAT('ALTER TABLE `async_task` ', @async_task_unique_drop_list));
PREPARE async_task_unique_drop_stmt FROM @async_task_unique_drop_sql;
EXECUTE async_task_unique_drop_stmt;
DEALLOCATE PREPARE async_task_unique_drop_stmt;
ALTER TABLE async_task ADD CONSTRAINT uk_async_task_task_id UNIQUE (owner_id, task_id);
ALTER TABLE async_task ADD CONSTRAINT fk_async_task_owner FOREIGN KEY (owner_id) REFERENCES app_user(id);
CREATE INDEX idx_async_task_owner ON async_task(owner_id);
ALTER TABLE model_runtime_endpoint ADD COLUMN owner_id BIGINT NULL;
SET @model_endpoint_unique_drop_list = (
    SELECT GROUP_CONCAT(CONCAT('DROP INDEX `', REPLACE(index_name, '`', '``'), '`') SEPARATOR ', ')
    FROM (
        SELECT index_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'model_runtime_endpoint' AND non_unique = 0
        GROUP BY index_name
        HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') = 'run_mode,capability'
    ) AS model_endpoint_unique_indexes
);
SET @model_endpoint_unique_drop_sql = IF(@model_endpoint_unique_drop_list IS NULL, 'SELECT 1',
    CONCAT('ALTER TABLE `model_runtime_endpoint` ', @model_endpoint_unique_drop_list));
PREPARE model_endpoint_unique_drop_stmt FROM @model_endpoint_unique_drop_sql;
EXECUTE model_endpoint_unique_drop_stmt;
DEALLOCATE PREPARE model_endpoint_unique_drop_stmt;
ALTER TABLE model_runtime_endpoint ADD CONSTRAINT uk_model_runtime_endpoint_owner_mode_capability
    UNIQUE (owner_id, run_mode, capability);
ALTER TABLE model_runtime_endpoint ADD CONSTRAINT fk_model_runtime_endpoint_owner FOREIGN KEY (owner_id) REFERENCES app_user(id);
CREATE INDEX idx_model_runtime_endpoint_owner ON model_runtime_endpoint(owner_id);

CREATE TABLE user_model_credential (
    owner_id BIGINT NOT NULL,
    provider VARCHAR(32) NOT NULL,
    encrypted_api_key TEXT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id, provider),
    CONSTRAINT fk_user_model_credential_owner FOREIGN KEY (owner_id) REFERENCES app_user(id)
) ENGINE=InnoDB;

CREATE TABLE user_model_runtime_setting (
    id BIGINT NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    active_mode VARCHAR(16) NOT NULL,
    api_validated_at DATETIME(6),
    local_validated_at DATETIME(6),
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_user_model_runtime_setting_owner UNIQUE (owner_id),
    CONSTRAINT fk_user_model_runtime_setting_owner FOREIGN KEY (owner_id) REFERENCES app_user(id)
) ENGINE=InnoDB;
