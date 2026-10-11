package com.coffer.desktop;

/** Runs maintenance in its own JVM so shutdown cannot hide unflushed restored changes. */
public final class BackupMaintenanceProbeMain {
    public static void main(String[] args) {
        System.exit(DesktopMaintenanceMain.run());
    }
}
