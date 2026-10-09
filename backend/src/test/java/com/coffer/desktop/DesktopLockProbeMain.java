package com.coffer.desktop;

import java.nio.file.Path;

/** A separate process must never obtain the first process's library lock. */
public final class DesktopLockProbeMain {
    public static void main(String[] args) {
        try (var data = DesktopDataDirectory.open(Path.of(args[0]), false)) {
            System.out.println("LOCK_ACQUIRED");
        } catch (DesktopStartupException failure) {
            System.out.println("LOCK_REJECTED=" + failure.reason());
            System.exit(23);
        }
    }
}
