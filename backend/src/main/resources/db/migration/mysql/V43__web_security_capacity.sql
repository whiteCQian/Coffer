CREATE TABLE web_quota_lock (id INT PRIMARY KEY, initialized BOOLEAN NOT NULL DEFAULT FALSE) ENGINE=InnoDB;
INSERT INTO web_quota_lock(id,initialized) VALUES(1,FALSE);
CREATE TABLE web_storage_allocation (
 key_hash CHAR(64) PRIMARY KEY, owner_id BIGINT NOT NULL, object_key VARCHAR(1024) NOT NULL,
 bytes BIGINT NOT NULL, state VARCHAR(16) NOT NULL, created_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_web_allocation_owner FOREIGN KEY(owner_id) REFERENCES app_user(id)
) ENGINE=InnoDB;
CREATE INDEX idx_web_allocation_owner ON web_storage_allocation(owner_id);
CREATE TABLE auth_rate_lock (id INT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO auth_rate_lock(id) VALUES(1);
CREATE TABLE auth_rate_bucket (key_hash CHAR(64) PRIMARY KEY, count BIGINT NOT NULL, expires_at BIGINT NOT NULL) ENGINE=InnoDB;
