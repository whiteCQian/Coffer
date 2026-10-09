ALTER TABLE inbox_import_record ADD COLUMN source_file_key VARCHAR(512);
ALTER TABLE inbox_import_record ADD COLUMN target_path VARCHAR(1024);
