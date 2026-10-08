CREATE TABLE runtime_alert (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 code VARCHAR(96) NOT NULL, severity VARCHAR(16) NOT NULL,
 first_seen DATETIME(6) NOT NULL, last_seen DATETIME(6) NOT NULL, resolved_at DATETIME(6)
) ENGINE=InnoDB;
CREATE INDEX idx_runtime_alert_active ON runtime_alert(code,resolved_at);
CREATE TABLE secret_rotation_event (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 key_id VARCHAR(16) NOT NULL, verified_count BIGINT NOT NULL, rewritten_count BIGINT NOT NULL, completed_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE secret_rotation_lock (id INT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO secret_rotation_lock(id) VALUES(1);
