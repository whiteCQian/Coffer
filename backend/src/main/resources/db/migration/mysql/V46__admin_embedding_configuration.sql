CREATE TABLE admin_embedding_setting (
 id BIGINT PRIMARY KEY, run_mode VARCHAR(16) NOT NULL, base_url VARCHAR(512) NOT NULL,
 model_name VARCHAR(128) NOT NULL, dimensions INT NOT NULL, encrypted_api_key TEXT,
 enabled BOOLEAN NOT NULL DEFAULT FALSE, validated_at TIMESTAMP NULL,
 configuration_version VARCHAR(64) NOT NULL, updated_at TIMESTAMP NOT NULL, revision BIGINT NOT NULL DEFAULT 0
);
INSERT INTO admin_embedding_setting(id,run_mode,base_url,model_name,dimensions,enabled,configuration_version,updated_at,revision)
 VALUES(1,'API','','',1024,FALSE,'unconfigured',CURRENT_TIMESTAMP,0);
