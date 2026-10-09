package com.coffer.desktop;

import java.nio.file.*;
import java.util.*;

/** New JVM replay, not a caught exception fixture: the first process halts after partial DDL and body removal. */
public final class UpgradeRollbackCrashProbeMain {
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException();Path parent=Path.of(args[1]);
        if(args[0].equals("crash")) {
            var fixture=new DesktopUpgradeRollbackTest();fixture.temp=parent;Path root=fixture.seed();
            Files.writeString(parent.resolve("expected-facts.json"),DesktopBackupService.JSON.writeValueAsString(SnapshotDatabase.facts(root)));
            new DesktopUpgradeService().upgrade(root,root.resolve("library"),"R35-crash-rollback-passphrase".toCharArray(),41,data->{fixture.partialMigration(data);System.out.println("R35_CRASH_AFTER_PARTIAL_MIGRATION");System.out.flush();Runtime.getRuntime().halt(73);});
        } else {
            Path root=parent.resolve("legacy-data");var result=new DesktopUpgradeService().recover(root);
            var expected=DesktopBackupService.JSON.readValue(Files.readAllBytes(parent.resolve("expected-facts.json")),new com.fasterxml.jackson.core.type.TypeReference<Map<String,SnapshotManifest.TableFact>>(){});
            if(!SnapshotDatabase.facts(root).equals(expected) || SnapshotDatabase.schema(root)!=40 || !Files.readString(root.resolve("library/users/1/files/中文旧资料.txt")).equals("旧用户正文-1"))throw new AssertionError("Rollback dataset mismatch");
            if(Files.exists(DesktopUpgradeService.journal(root)))throw new AssertionError("Upgrade journal not completed");
            System.out.println("R35_NEW_JVM_ROLLBACK_PASS: "+result.status());
        }
    }
}
