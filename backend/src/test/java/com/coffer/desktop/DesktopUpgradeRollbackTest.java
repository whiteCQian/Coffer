package com.coffer.desktop;

import com.coffer.service.SecretCryptoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class DesktopUpgradeRollbackTest {
    @TempDir Path temp;private static final char[] PASSWORD="R35-upgrade-rollback-passphrase".toCharArray();
    Path seed() throws Exception {
        Path root=temp.resolve("legacy-data");
        try(var directory=DesktopDataDirectory.open(root,true)) {
            var source=new DriverManagerDataSource("jdbc:h2:file:"+root.resolve("database/coffer").toString().replace('\\','/'),"sa","");
            org.flywaydb.core.Flyway.configure().dataSource(source).locations("classpath:db/migration/h2").target("40").load().migrate();
            var jdbc=new JdbcTemplate(source);var crypto=new SecretCryptoService(directory.masterKey(),new MockEnvironment());crypto.initialize();var identity=directory.identity();
            jdbc.update("INSERT INTO desktop_library_binding(id,format_version,installation_id,library_id,key_check,library_root) VALUES(1,?,?,?,?,?)",identity.formatVersion(),identity.installationId(),identity.libraryId(),crypto.encrypt("coffer-desktop-v1:"+identity.installationId()+":"+identity.libraryId()),directory.libraryRoot().toString());
            var encoder=org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder();
            for(int owner=1;owner<=2;owner++) {
                jdbc.update("INSERT INTO app_user(id,username,password_hash,role,enabled,created_at,updated_at) VALUES(?,?,?,'USER',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",owner,"r35-old-user-"+owner,encoder.encode("R35-user-pass"));
                String key="users/"+owner+"/files/中文旧资料.txt";Path body=directory.libraryRoot().resolve(key);Files.createDirectories(body.getParent());Files.writeString(body,"旧用户正文-"+owner);
                jdbc.update("INSERT INTO file_metadata(owner_id,file_name,file_size,file_type,storage_path,content_sha256,status,category,archived,revision,upload_time) VALUES(?,?,?,?,?,?,'COMPLETED','REPORT',false,4,CURRENT_TIMESTAMP)",owner,"中文旧资料.txt",Files.size(body),"txt",key,SnapshotDatabase.sha256(body));
            }
        }
        return root;
    }
    void partialMigration(Path root) throws Exception {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(SnapshotDatabase.url(root,true),"sa",""));
        jdbc.update("UPDATE app_user SET username='partially-migrated' WHERE id=1");jdbc.execute("ALTER TABLE work_save_intent ADD COLUMN failed_upgrade_marker VARCHAR(20)");
        Files.delete(root.resolve("library/users/1/files/中文旧资料.txt"));
    }
    @Test void partialDdlAndMissingFileRestoreTheWholeOldDatasetAfterMigrationFailure() throws Exception {
        Path root=seed();var before=SnapshotDatabase.facts(root);String key=Files.readString(root.resolve(DesktopDataDirectory.KEY_FILE));
        var result=new DesktopUpgradeService().upgrade(root,root.resolve("library"),PASSWORD,41,data->{partialMigration(data);throw new java.io.IOException("injected migration failure");});
        assertThat(result.status()).isEqualTo("MIGRATION_FAILED_ROLLED_BACK");assertThat(SnapshotDatabase.schema(root)).isEqualTo(40);
        assertThat(SnapshotDatabase.facts(root)).isEqualTo(before);assertThat(root.resolve(DesktopDataDirectory.KEY_FILE)).hasContent(key);
        assertThat(root.resolve("library/users/1/files/中文旧资料.txt")).hasContent("旧用户正文-1");assertThat(root.resolve("library/users/2/files/中文旧资料.txt")).hasContent("旧用户正文-2");
        assertThat(DesktopUpgradeService.journal(root)).doesNotExist();assertThat(Path.of(result.backupPath()).getFileName().toString()).endsWith(".cofferbackup");
    }
    @Test void interruptedMigrationKeepsJournalAndCanReplayRollbackWithoutCreatingANewEmptyLibrary() throws Exception {
        Path root=seed();var before=SnapshotDatabase.facts(root);var service=new DesktopUpgradeService();
        assertThatThrownBy(()->service.upgrade(root,root.resolve("library"),PASSWORD,41,data->{partialMigration(data);throw new AssertionError("abrupt process interruption fixture");})).isInstanceOf(AssertionError.class);
        assertThat(DesktopUpgradeService.journal(root)).exists();assertThat(DesktopUpgradeService.allowedHealthProbe(root,"unknown")).isFalse();
        assertThat(new DesktopUpgradeService().recover(root).status()).isEqualTo("OLD_DATA_RESTORED_VERIFIED");
        assertThat(SnapshotDatabase.facts(root)).isEqualTo(before);assertThat(root.resolve("library/users/1/files/中文旧资料.txt")).hasContent("旧用户正文-1");
    }
    @Test void successfulMigrationKeepsRollbackUntilTheNewBackendHealthHasBeenConfirmed() throws Exception {
        Path root=seed();var service=new DesktopUpgradeService();var upgraded=service.upgrade(root,root.resolve("library"),PASSWORD,41);
        assertThat(upgraded.status()).isEqualTo("MIGRATED_AWAITING_HEALTH");assertThat(SnapshotDatabase.schema(root)).isEqualTo(41);
        assertThat(DesktopUpgradeService.allowedHealthProbe(root,upgraded.backupId())).isTrue();assertThat(DesktopUpgradeService.allowedHealthProbe(root,"wrong-id")).isFalse();
        service.commitHealthy(root);assertThat(DesktopUpgradeService.journal(root)).doesNotExist();assertThat(Path.of(upgraded.backupPath())).exists();
    }
}
