package com.coffer.governance;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks that H2 and MySQL expose the same C05 preview contract. */
class GovernancePreviewMigrationScriptsTest {

    private static final String[] REQUIRED_SCHEMA_TOKENS = {
            "governance_preview_batch",
            "governance_preview_item",
            "preview_id",
            "preview_source",
            "run_mode",
            "request_id",
            "source_revision",
            "source_etag",
            "suggested_file_name",
            "suggested_category",
            "suggested_path",
            "suggested_summary",
            "suggested_tags",
            "analysis_model",
            "analysis_mode",
            "status",
            "expires_at",
            "uk_governance_preview_batch_preview_id",
            "uk_governance_preview_batch_request_id",
            "uk_governance_preview_item_preview_file",
            "idx_governance_preview_batch_status_expires",
            "idx_governance_preview_item_preview_status",
            "revision",
            "content_etag"
    };

    @Test
    void h2AndMySqlMigrationsContainTheSamePreviewContract() throws IOException {
        String h2 = read("db/migration/h2/V10__add_governance_preview.sql").toLowerCase();
        String mysql = read("db/migration/mysql/V10__add_governance_preview.sql").toLowerCase();

        for (String token : REQUIRED_SCHEMA_TOKENS) {
            assertThat(h2).as("H2 migration token %s", token).contains(token);
            assertThat(mysql).as("MySQL migration token %s", token).contains(token);
        }
    }

    private String read(String path) throws IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
