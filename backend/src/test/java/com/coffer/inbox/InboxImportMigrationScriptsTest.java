package com.coffer.inbox;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks that H2 and MySQL migrations expose the same C04 schema contract. */
class InboxImportMigrationScriptsTest {

    private static final String[] REQUIRED_SCHEMA_TOKENS = {
            "inbox_import_record",
            "snapshot_key",
            "source_path",
            "source_size",
            "source_modified_at",
            "content_sha256",
            "stable_observations",
            "attempt_count",
            "task_id",
            "file_id",
            "uk_inbox_import_record_snapshot_key",
            "idx_inbox_import_record_status_seen",
            "idx_inbox_import_record_content_sha256",
            "idx_inbox_import_record_source_path"
    };

    @Test
    void h2AndMySqlMigrationsContainTheSameInboxImportContract() throws IOException {
        String h2 = read("db/migration/h2/V9__add_inbox_import_record.sql");
        String mysql = read("db/migration/mysql/V9__add_inbox_import_record.sql");

        for (String token : REQUIRED_SCHEMA_TOKENS) {
            assertThat(h2.toLowerCase()).as("H2 migration token %s", token).contains(token);
            assertThat(mysql.toLowerCase()).as("MySQL migration token %s", token).contains(token);
        }
    }

    private String read(String path) throws IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
