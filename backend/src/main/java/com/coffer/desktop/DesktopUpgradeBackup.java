package com.coffer.desktop;

import org.springframework.core.env.Environment;
import java.nio.file.Path;
import static com.coffer.desktop.DesktopStartupException.Reason.*;

/** Installed startup only checks schema; upgrades use the encrypted maintenance transaction, never plaintext copies. */
final class DesktopUpgradeBackup {
    static Path guard(DesktopDataDirectory directory, Environment environment) {
        if(directory.initializedNow())return null;
        String expected=environment.getRequiredProperty("COFFER_DESKTOP_SCHEMA_VERSION");
        try {
            int actual=SnapshotDatabase.schema(directory.root()),target=Integer.parseInt(expected);
            if(actual==target)return null;
            if(actual>target)throw new DesktopStartupException(DOWNGRADE_REFUSED);
            throw new DesktopStartupException(UPGRADE_BACKUP_REQUIRED);
        } catch(DesktopStartupException fixed) {throw fixed;}
        catch(Exception invalid) {throw new DesktopStartupException(UPGRADE_BACKUP_REQUIRED);}
    }
}