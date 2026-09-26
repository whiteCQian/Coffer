package com.coffer.auth;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;

class CredentialIdentityMigrationTest {
    @Test void upgradePreservesTwoOwnersWithTheSameProvider() throws Exception {
        String url = "jdbc:h2:mem:credential-upgrade;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration/h2").target("17").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            sql.executeUpdate("insert into app_user(username,password_hash,role,enabled,created_at,updated_at) values ('a','x','USER',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),('b','y','USER',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            sql.executeUpdate("insert into user_model_credential(owner_id,provider,encrypted_api_key,updated_at) values (1,'DEEPSEEK','cipher-a',CURRENT_TIMESTAMP),(2,'DEEPSEEK','cipher-b',CURRENT_TIMESTAMP)");
        }
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration/h2").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("select count(*),count(distinct id) from user_model_credential")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(2); assertThat(rows.getInt(2)).isEqualTo(2);
            }
            try (var rows = sql.executeQuery("select encrypted_api_key from user_model_credential where owner_id=2")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("cipher-b");
            }
        }
    }
}
