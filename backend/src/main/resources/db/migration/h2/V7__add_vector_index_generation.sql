ALTER TABLE file_metadata ADD COLUMN IF NOT EXISTS vector_index_generation VARCHAR(64) NULL;
