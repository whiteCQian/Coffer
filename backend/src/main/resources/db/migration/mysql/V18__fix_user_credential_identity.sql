ALTER TABLE user_model_credential ADD UNIQUE KEY uk_user_credential_owner_provider (owner_id, provider);
ALTER TABLE user_model_credential DROP PRIMARY KEY, ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY;
