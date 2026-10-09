package com.coffer.desktop;

import com.coffer.file.infrastructure.storage.SafeLocalPaths;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** A durable upgrade journal prevents startup of a mixed dataset and replays verified whole-data rollback. */
public final class DesktopUpgradeService {
    record Journal(int formatVersion,String dataRoot,String libraryRoot,String stageRoot,String manifestSha256,String phase) { }
    @FunctionalInterface interface Migration { void apply(Path data) throws Exception; }
    public record Result(String status,String backupPath,String backupId) { }
    public static Path journal(Path root){return root.toAbsolutePath().normalize().resolveSibling(root.getFileName()+".coffer-upgrade.json");}
    static boolean allowedHealthProbe(Path root,String backupId) {
        try{var intent=readJournal(root);return intent.phase().equals("AWAITING_HEALTH") && manifest(intent).backupId().equals(backupId);}catch(Exception invalid){return false;}
    }

    public Result upgrade(Path root,Path library,char[] password,int targetSchema) throws Exception {
        return upgrade(root,library,password,targetSchema,data->{var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(SnapshotDatabase.url(data,true),"sa","");org.flywaydb.core.Flyway.configure().dataSource(source).locations("classpath:db/migration/h2").target(Integer.toString(targetSchema)).validateOnMigrate(true).baselineOnMigrate(false).load().migrate();});
    }
    Result upgrade(Path root,Path library,char[] password,int targetSchema,Migration migrate) throws Exception {
        root=root.toAbsolutePath().normalize();library=library.toAbsolutePath().normalize();if(Files.exists(journal(root),LinkOption.NOFOLLOW_LINKS))throw new IOException("UPGRADE_RECOVERY_REQUIRED");
        var service=new DesktopBackupService();Path heldStage=null;String archive,id;
        try(var directory=DesktopDataDirectory.open(root,false,library,false)) {
            if(SnapshotDatabase.schema(root)>targetSchema)throw new IOException("DOWNGRADE_REFUSED");
            Path copies=root.resolve(".upgrade-backups");if(!Files.exists(copies,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(copies);SafeLocalPaths.directory(copies);DesktopDataDirectory.restrict(copies);
            var backup=service.createLocked(directory,copies,password,new IndependentStorage.Evidence(false,"LOCAL_ROLLBACK_ONLY",""),Map.of("purpose","upgrade-rollback"));archive=backup.packagePath();id=backup.backupId();
            var verified=service.verify(Path.of(archive),password,root.getParent());heldStage=verified.staging();
            Path definition=heldStage.resolve("verified-manifest.json");writeJson(definition,verified.manifest());
            var intent=new Journal(1,root.toString(),library.toString(),heldStage.toString(),SnapshotDatabase.sha256(definition),"MIGRATING");writeJson(journal(root),intent);
            try {
                migrate.apply(root);
                if(SnapshotDatabase.schema(root)!=targetSchema)throw new IOException("UPGRADE_SCHEMA_MISMATCH");
                SnapshotDatabase.validate(directory);writeJson(journal(root),new Journal(1,root.toString(),library.toString(),heldStage.toString(),intent.manifestSha256(),"AWAITING_HEALTH"));
                return new Result("MIGRATED_AWAITING_HEALTH",archive,id);
            } catch(Exception failure) {
                // The original encrypted package and local verified stage survive until rollback verification succeeds.
                writeJson(journal(root),new Journal(1,root.toString(),library.toString(),heldStage.toString(),intent.manifestSha256(),"ROLLING_BACK"));
            }
        }
        recover(root);
        return new Result("MIGRATION_FAILED_ROLLED_BACK",archive,id);
    }
    public Result recover(Path root) throws Exception {
        var intent=readJournal(root);Path library=Path.of(intent.libraryRoot()),stage=Path.of(intent.stageRoot());var manifest=manifest(intent);
        Path data=stage.resolve("dataset");
        try(var checked=DesktopDataDirectory.open(data,false)){SnapshotDatabase.validate(checked);if(!SnapshotDatabase.facts(data).equals(manifest.tables()))throw new IOException("UPGRADE_STAGE_DATABASE_CHANGED");}
        validateStageFiles(data,manifest);
        var rolling=new Journal(1,intent.dataRoot(),intent.libraryRoot(),intent.stageRoot(),intent.manifestSha256(),"ROLLING_BACK");writeJson(journal(root),rolling);
        try(var locks=new RawLocks(root,library)) {
            Path quarantine=stage.resolve("quarantine");if(!Files.exists(quarantine))Files.createDirectory(quarantine);DesktopDataDirectory.restrict(quarantine);
            applyArea(data,root,true,quarantine.resolve("data"));applyArea(data.resolve("library"),library,false,quarantine.resolve("library"));
        }
        // Locks are closed before Directory opens its own channels; the journal keeps normal startup forbidden.
        try(var restored=DesktopDataDirectory.open(root,false,library,false)) {
            SnapshotDatabase.validate(restored);
            if(!SnapshotDatabase.facts(root).equals(manifest.tables()))throw new IOException("UPGRADE_ROLLBACK_FACTS_MISMATCH");
            SnapshotDatabase.relocate(root,library,library,manifest);SnapshotDatabase.validate(restored);
        }
        Files.delete(journal(root));DesktopBackupService.removeTree(stage,root.getParent());return new Result("OLD_DATA_RESTORED_VERIFIED",null,manifest.backupId());
    }
    public void commitHealthy(Path root) throws Exception {
        var intent=readJournal(root);if(!intent.phase().equals("AWAITING_HEALTH"))throw new IOException("UPGRADE_NOT_AWAITING_HEALTH");
        manifest(intent);Files.delete(journal(root));DesktopBackupService.removeTree(Path.of(intent.stageRoot()),root.toAbsolutePath().getParent());
    }
    private static Journal readJournal(Path root) throws Exception {
        root=root.toAbsolutePath().normalize();Path file=journal(root);SafeLocalPaths.file(file);if(Files.size(file)>8192)throw new IOException("UPGRADE_JOURNAL_INVALID");
        var intent=DesktopBackupService.JSON.readValue(Files.readAllBytes(file),Journal.class);
        Path stage=Path.of(intent.stageRoot()).toAbsolutePath().normalize();
        if(intent.formatVersion()!=1 || !Path.of(intent.dataRoot()).toAbsolutePath().normalize().equals(root) || !stage.getParent().equals(root.getParent()) || !stage.getFileName().toString().startsWith(".coffer-verify-"))throw new IOException("UPGRADE_JOURNAL_INVALID");
        SafeLocalPaths.directory(stage);SafeLocalPaths.directory(Path.of(intent.libraryRoot()));return intent;
    }
    private static SnapshotManifest manifest(Journal intent) throws Exception {
        Path definition=Path.of(intent.stageRoot()).resolve("verified-manifest.json");SafeLocalPaths.file(definition);
        if(!SnapshotDatabase.sha256(definition).equals(intent.manifestSha256()))throw new IOException("UPGRADE_STAGE_MANIFEST_CHANGED");
        var manifest=DesktopBackupService.JSON.readValue(Files.readAllBytes(definition),SnapshotManifest.class);
        if(!Path.of(manifest.sourceDataRoot()).toAbsolutePath().normalize().equals(Path.of(intent.dataRoot()).toAbsolutePath().normalize())
                || !Path.of(manifest.sourceLibraryRoot()).toAbsolutePath().normalize().equals(Path.of(intent.libraryRoot()).toAbsolutePath().normalize()))throw new IOException("UPGRADE_JOURNAL_DATASET_MISMATCH");
        return manifest;
    }
    private static void validateStageFiles(Path root,SnapshotManifest manifest) throws Exception {
        for(var entry:manifest.entries()){
            if(entry.path().equals("database/h2.zip"))continue;
            Path file=entry.path().startsWith("data/")?root.resolve(entry.path().substring(5)):root.resolve(entry.path());
            if(!file.normalize().startsWith(root) || SafeLocalPaths.file(file).size()!=entry.size() || !SnapshotDatabase.sha256(file).equals(entry.sha256()))throw new IOException("UPGRADE_STAGE_FILE_CHANGED");
        }
    }
    private static void applyArea(Path source,Path target,boolean data,Path quarantine) throws Exception {
        Files.createDirectories(quarantine);DesktopDataDirectory.restrict(quarantine);
        Map<String,Path> desired=files(source,data),existing=files(target,data);
        for(var entry:existing.entrySet()){
            Path wanted=desired.get(entry.getKey());if(wanted!=null && SnapshotDatabase.sha256(entry.getValue()).equals(SnapshotDatabase.sha256(wanted)))continue;
            Path kept=quarantine.resolve(entry.getKey());Files.createDirectories(kept.getParent());if(Files.exists(kept))kept=kept.resolveSibling(kept.getFileName()+"."+UUID.randomUUID());Files.move(entry.getValue(),kept,StandardCopyOption.ATOMIC_MOVE);
        }
        for(var entry:desired.entrySet()){
            Path output=target.resolve(entry.getKey());
            if(!Files.exists(output,LinkOption.NOFOLLOW_LINKS)){
                Files.createDirectories(output.getParent());
                try(var in=Files.newInputStream(entry.getValue(),LinkOption.NOFOLLOW_LINKS);var out=FileChannel.open(output,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                    byte[] buffer=new byte[64*1024];int count;while((count=in.read(buffer))!=-1){var bytes=ByteBuffer.wrap(buffer,0,count);while(bytes.hasRemaining())out.write(bytes);}out.force(true);
                }
                DesktopDataDirectory.restrict(output);
            }
            Files.setLastModifiedTime(output,Files.getLastModifiedTime(entry.getValue()));
            if(!SnapshotDatabase.sha256(output).equals(SnapshotDatabase.sha256(entry.getValue())))throw new IOException("UPGRADE_ROLLBACK_COPY_MISMATCH");
        }
    }
    private static Map<String,Path> files(Path root,boolean data) throws Exception {
        var entries=new TreeMap<String,Path>();Files.walkFileTree(root,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path,java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                SafeLocalPaths.directory(path);Path relative=root.relativize(path);
                if(data && !relative.toString().isEmpty() && Set.of("library","logs",".coffer-runtime",".upgrade-backups",".backup-staging").contains(relative.getName(0).toString()))return FileVisitResult.SKIP_SUBTREE;return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path,java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {String name=path.getFileName().toString();if(!name.equals(".coffer-desktop.lock") && !name.equals(".coffer-library.lock")){SafeLocalPaths.file(path);entries.put(root.relativize(path).toString(),path);}return FileVisitResult.CONTINUE;}
        });return entries;
    }
    static void writeJson(Path file,Object value) throws Exception {
        SafeLocalPaths.directory(file.getParent());Path stage=Files.createTempFile(file.getParent(),".coffer-journal-",".tmp");DesktopDataDirectory.restrict(stage);
        try{byte[] encoded=DesktopBackupService.JSON.writeValueAsBytes(value);try(var channel=FileChannel.open(stage,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(encoded);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}Files.move(stage,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);DesktopDataDirectory.restrict(file);}finally{Files.deleteIfExists(stage);}
    }
    private static final class RawLocks implements AutoCloseable {
        private FileChannel data,library;private FileLock first,second;
        RawLocks(Path root,Path lib) throws Exception {
            SafeLocalPaths.directory(root);SafeLocalPaths.directory(lib);
            try{data=FileChannel.open(root.resolve(".coffer-desktop.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);first=data.tryLock();if(first==null)throw new IOException("DATA_IN_USE");library=FileChannel.open(lib.resolve(".coffer-library.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);second=library.tryLock();if(second==null)throw new IOException("LIBRARY_IN_USE");}
            catch(Exception failed){close();throw failed;}
        }
        @Override public void close() throws IOException {if(second!=null && second.isValid())second.release();if(library!=null)library.close();if(first!=null && first.isValid())first.release();if(data!=null)data.close();}
    }
}
