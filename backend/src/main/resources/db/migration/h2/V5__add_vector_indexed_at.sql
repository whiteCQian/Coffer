ALTER TABLE file_metadata
    ADD COLUMN vector_indexed_at TIMESTAMP NULL;

CREATE INDEX idx_file_metadata_vector_indexed_at
    ON file_metadata(vector_indexed_at);
