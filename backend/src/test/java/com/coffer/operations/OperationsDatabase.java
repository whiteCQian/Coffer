package com.coffer.operations;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;

final class OperationsDatabase {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:r26_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    OperationsDatabase() {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/h2").load().migrate();
    }
    long owner(boolean enabled) {
        jdbc.update("INSERT INTO app_user(username,password_hash,role,enabled,created_at,updated_at) VALUES(?,'x','USER',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", UUID.randomUUID().toString(), enabled);
        return jdbc.queryForObject("SELECT MAX(id) FROM app_user", Long.class);
    }
}
