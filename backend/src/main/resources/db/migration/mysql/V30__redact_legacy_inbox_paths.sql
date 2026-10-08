-- Historical imports stored absolute source paths. Keep only the owner-local file name.
UPDATE inbox_import_record SET source_path = source_file_name;
