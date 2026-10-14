ALTER TABLE app_user ADD COLUMN deletion_status VARCHAR(16);
CREATE TABLE account_deletion_job (
 target_user_id BIGINT PRIMARY KEY, actor_user_id BIGINT NOT NULL, actor_username VARCHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL, attempts INT NOT NULL DEFAULT 0,
 next_attempt_at TIMESTAMP NOT NULL, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL
);
