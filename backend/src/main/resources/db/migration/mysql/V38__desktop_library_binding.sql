CREATE TABLE desktop_library_binding (
 id INT PRIMARY KEY,
 format_version INT NOT NULL,
 installation_id VARCHAR(36) NOT NULL,
 library_id VARCHAR(36) NOT NULL,
 key_check VARCHAR(512) NOT NULL
) ENGINE=InnoDB;
