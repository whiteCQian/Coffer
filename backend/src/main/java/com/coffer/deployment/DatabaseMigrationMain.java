package com.coffer.deployment;

import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;

/** Short-lived deployment job. The web process never receives the DDL credential. */
public final class DatabaseMigrationMain {
    public static void main(String[] args) throws Exception {
        try (var source = new com.zaxxer.hikari.HikariDataSource()) {
        source.setJdbcUrl(System.getenv("COFFER_DB_URL"));
        source.setUsername("coffer_migrate");
        source.setPassword(Files.readString(Path.of("/run/secrets/db_migrate_password")).trim());
        DatabaseTls.configure(source);
        Flyway.configure().dataSource(source)
                .locations("classpath:db/migration/mysql").baselineOnMigrate(false)
                .validateOnMigrate(true).cleanDisabled(true).load().migrate();
        }
    }
}
