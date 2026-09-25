package com.coffer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that a new H2 database is initialized by Flyway before JPA validation. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flyway_migration_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.flyway.locations=classpath:db/migration/h2",
        "minio.access-key=test-access-key",
        "minio.secret-key=test-secret-key"
})
@ActiveProfiles("dev")
class FlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesAllH2MigrationsAndCreatesApplicationTables() {
        Integer fileMetadataTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'FILE_METADATA'", Integer.class);
        Integer credentialTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'MODEL_CREDENTIAL'", Integer.class);
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\"", Integer.class);
        Integer applicationMigrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" IN ('1', '2', '3', '4', '5')", Integer.class);
        Integer vectorIndexedColumns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'FILE_METADATA' AND COLUMN_NAME = 'VECTOR_INDEXED_AT'", Integer.class);
        Integer operationBatchTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME = 'ARCHIVE_OPERATION_BATCH'", Integer.class);
        Integer operationItemTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME = 'ARCHIVE_OPERATION_ITEM'", Integer.class);
        Integer operationItemColumns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'ARCHIVE_OPERATION_ITEM' "
                        + "AND COLUMN_NAME IN ('ITEM_KEY', 'SOURCE_PATH', 'TARGET_PATH', "
                        + "'EXECUTION_STATUS', 'EXECUTION_STEP', 'ROLLBACK_STATUS')", Integer.class);
        Integer operationBatchUniqueConstraints = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = 'ARCHIVE_OPERATION_BATCH' "
                        + "AND CONSTRAINT_NAME IN ('UK_ARCHIVE_OPERATION_BATCH_BATCH_ID', "
                        + "'UK_ARCHIVE_OPERATION_BATCH_REQUEST_ID')", Integer.class);
        Integer operationItemUniqueConstraints = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = 'ARCHIVE_OPERATION_ITEM' "
                        + "AND CONSTRAINT_NAME IN ('UK_ARCHIVE_OPERATION_ITEM_ITEM_KEY', "
                        + "'UK_ARCHIVE_OPERATION_ITEM_BATCH_FILE')", Integer.class);
        Integer operationMigration = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '8'", Integer.class);
        Integer inboxImportTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME = 'INBOX_IMPORT_RECORD'", Integer.class);
        Integer inboxImportColumns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'INBOX_IMPORT_RECORD' "
                        + "AND COLUMN_NAME IN ('SNAPSHOT_KEY', 'SOURCE_PATH', 'CONTENT_SHA256', "
                        + "'STABLE_OBSERVATIONS', 'STATUS', 'TASK_ID')", Integer.class);
        Integer inboxImportUniqueConstraints = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = 'INBOX_IMPORT_RECORD' "
                        + "AND CONSTRAINT_NAME = 'UK_INBOX_IMPORT_RECORD_SNAPSHOT_KEY'", Integer.class);
        Integer inboxImportMigration = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '9'", Integer.class);
        Integer previewBatchTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME = 'GOVERNANCE_PREVIEW_BATCH'", Integer.class);
        Integer previewItemTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME = 'GOVERNANCE_PREVIEW_ITEM'", Integer.class);
        Integer previewBatchColumns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'GOVERNANCE_PREVIEW_BATCH' "
                        + "AND COLUMN_NAME IN ('PREVIEW_ID', 'PREVIEW_SOURCE', 'RUN_MODE', "
                        + "'REQUEST_ID', 'STATUS', 'EXPIRES_AT')", Integer.class);
        Integer previewItemColumns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'GOVERNANCE_PREVIEW_ITEM' "
                        + "AND COLUMN_NAME IN ('PREVIEW_ID', 'FILE_ID', 'SOURCE_REVISION', "
                        + "'SOURCE_ETAG', 'SUGGESTED_PATH', 'SUGGESTED_TAGS', 'STATUS')", Integer.class);
        Integer previewUniqueConstraints = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = 'GOVERNANCE_PREVIEW_BATCH' "
                        + "AND CONSTRAINT_NAME IN ('UK_GOVERNANCE_PREVIEW_BATCH_PREVIEW_ID', "
                        + "'UK_GOVERNANCE_PREVIEW_BATCH_REQUEST_ID')", Integer.class);
        Integer previewMigration = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '10'", Integer.class);
        Integer revisionColumns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'FILE_METADATA' AND COLUMN_NAME IN ('REVISION', 'CONTENT_ETAG')", Integer.class);
        Integer runtimeSettingTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME = 'MODEL_RUNTIME_SETTING'", Integer.class);
        Integer runtimeSettingRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM MODEL_RUNTIME_SETTING WHERE ID = 1 AND ACTIVE_MODE = 'API'", Integer.class);
        Integer taskRunModeColumns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'ASYNC_TASK' AND COLUMN_NAME = 'RUN_MODE'", Integer.class);
        Integer runtimeMigration = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '12'", Integer.class);
        Integer runtimeEndpointTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME = 'MODEL_RUNTIME_ENDPOINT'", Integer.class);
        Integer runtimeEndpointColumns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'MODEL_RUNTIME_ENDPOINT' "
                        + "AND COLUMN_NAME IN ('RUN_MODE', 'CAPABILITY', 'BASE_URL', 'MODEL_NAME', 'ENCRYPTED_API_KEY')", Integer.class);
        Integer runtimeEndpointUniqueConstraints = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = 'MODEL_RUNTIME_ENDPOINT' "
                        + "AND CONSTRAINT_NAME = 'UK_MODEL_RUNTIME_ENDPOINT_MODE_CAPABILITY'", Integer.class);
        Integer runtimeEndpointMigration = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '13'", Integer.class);
        Integer archiveNameLockTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME = 'ARCHIVE_OBJECT_NAME_ALLOCATOR_LOCK'", Integer.class);
        Integer archiveNameReservationTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME = 'ARCHIVE_OBJECT_NAME_RESERVATION'", Integer.class);
        Integer archiveNameReservationColumns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'ARCHIVE_OBJECT_NAME_RESERVATION' "
                        + "AND COLUMN_NAME IN ('BUSINESS_DATE', 'CATEGORY_SLUG', 'NORMALIZED_FILE_NAME', 'SEQUENCE_NUMBER')",
                Integer.class);
        Integer archiveNameMigration = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '14'", Integer.class);

        assertThat(fileMetadataTables).isEqualTo(1);
        assertThat(credentialTables).isEqualTo(1);
        assertThat(migrationCount).isGreaterThanOrEqualTo(5);
        assertThat(applicationMigrations).isEqualTo(5);
        assertThat(vectorIndexedColumns).isEqualTo(1);
        assertThat(operationBatchTables).isEqualTo(1);
        assertThat(operationItemTables).isEqualTo(1);
        assertThat(operationItemColumns).isEqualTo(6);
        assertThat(operationBatchUniqueConstraints).isEqualTo(2);
        assertThat(operationItemUniqueConstraints).isEqualTo(2);
        assertThat(operationMigration).isEqualTo(1);
        assertThat(inboxImportTables).isEqualTo(1);
        assertThat(inboxImportColumns).isEqualTo(6);
        assertThat(inboxImportUniqueConstraints).isEqualTo(1);
        assertThat(inboxImportMigration).isEqualTo(1);
        assertThat(previewBatchTables).isEqualTo(1);
        assertThat(previewItemTables).isEqualTo(1);
        assertThat(previewBatchColumns).isEqualTo(6);
        assertThat(previewItemColumns).isEqualTo(7);
        assertThat(previewUniqueConstraints).isEqualTo(2);
        assertThat(previewMigration).isEqualTo(1);
        assertThat(revisionColumns).isEqualTo(2);
        assertThat(runtimeSettingTables).isEqualTo(1);
        assertThat(runtimeSettingRows).isEqualTo(1);
        assertThat(taskRunModeColumns).isEqualTo(1);
        assertThat(runtimeMigration).isEqualTo(1);
        assertThat(runtimeEndpointTables).isEqualTo(1);
        assertThat(runtimeEndpointColumns).isEqualTo(5);
        assertThat(runtimeEndpointUniqueConstraints).isEqualTo(1);
        assertThat(runtimeEndpointMigration).isEqualTo(1);
        assertThat(archiveNameLockTables).isEqualTo(1);
        assertThat(archiveNameReservationTables).isEqualTo(1);
        assertThat(archiveNameReservationColumns).isEqualTo(4);
        assertThat(archiveNameMigration).isEqualTo(1);
    }
}
