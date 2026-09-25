package com.coffer.governance;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks that H2 and MySQL migrations expose the same C03 schema contract. */
class ArchiveOperationMigrationScriptsTest {

    private static final String[] REQUIRED_SCHEMA_TOKENS = {
            "archive_operation_batch",
            "archive_operation_item",
            "batch_id",
            "request_id",
            "item_key",
            "file_id",
            "execution_status",
            "execution_step",
            "rollback_status",
            "uk_archive_operation_batch_batch_id",
            "uk_archive_operation_batch_request_id",
            "uk_archive_operation_item_item_key",
            "uk_archive_operation_item_batch_file",
            "idx_archive_operation_batch_status_created",
            "idx_archive_operation_batch_rollback_created",
            "idx_archive_operation_item_batch_status",
            "idx_archive_operation_item_file_created",
            "idx_archive_operation_item_rollback_status"
    };

    @Test
    void h2AndMySqlMigrationsContainTheSameLedgerContract() throws IOException {
        String h2 = read("db/migration/h2/V8__add_archive_operation_ledger.sql");
        String mysql = read("db/migration/mysql/V8__add_archive_operation_ledger.sql");

        for (String token : REQUIRED_SCHEMA_TOKENS) {
            assertThat(h2.toLowerCase()).as("H2 migration token %s", token).contains(token);
            assertThat(mysql.toLowerCase()).as("MySQL migration token %s", token).contains(token);
        }
    }

    private String read(String path) throws IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
