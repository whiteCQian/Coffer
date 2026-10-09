package com.coffer.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class DesktopUpgradeBackupTest {
    @TempDir Path temp;
    @Test void installedStartupCannotSilentlyProducePlaintextUpgradeCopiesOrDowngrade() {
        Path root=temp.resolve("data");
        try(var directory=DesktopDataDirectory.open(root,true)) {
            var source=new DriverManagerDataSource("jdbc:h2:file:"+root.resolve("database/coffer").toString().replace('\\','/'),"sa","");
            org.flywaydb.core.Flyway.configure().dataSource(source).locations("classpath:db/migration/h2").target("40").load().migrate();
        }
        try(var directory=DesktopDataDirectory.open(root,false)) {
            var environment=new MockEnvironment().withProperty("COFFER_DESKTOP_SCHEMA_VERSION","41").withProperty("coffer.desktop.upgrade-with-backup","true");
            assertThatThrownBy(()->DesktopUpgradeBackup.guard(directory,environment)).isInstanceOfSatisfying(DesktopStartupException.class,error->assertThat(error.reason()).isEqualTo(DesktopStartupException.Reason.UPGRADE_BACKUP_REQUIRED));
            assertThat(root.resolve(".upgrade-backups")).doesNotExist();
            environment.withProperty("COFFER_DESKTOP_SCHEMA_VERSION","39");
            assertThatThrownBy(()->DesktopUpgradeBackup.guard(directory,environment)).isInstanceOfSatisfying(DesktopStartupException.class,error->assertThat(error.reason()).isEqualTo(DesktopStartupException.Reason.DOWNGRADE_REFUSED));
            environment.withProperty("COFFER_DESKTOP_SCHEMA_VERSION","40");assertThat(DesktopUpgradeBackup.guard(directory,environment)).isNull();
        }
    }
}