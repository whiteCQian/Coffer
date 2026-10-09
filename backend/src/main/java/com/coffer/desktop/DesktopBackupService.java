package com.coffer.desktop;

import com.coffer.file.infrastructure.storage.SafeLocalPaths;
import com.coffer.file.application.LocalImportSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

/** Offline whole-dataset backup/restore. Export never writes plaintext to its destination medium. */
public final class DesktopBackupService {
    static final ObjectMapper JSON=new ObjectMapper();
    private static final int MAX_ENTRIES=200_000;
    private static final long MAX_EXPANDED=1024L*1024*1024*1024;
    public record Result(String status,String packagePath,String packageSha256,boolean independent,long users,long files,long ledgerRows,String backupId) { }
    record Verified(Path staging,SnapshotManifest manifest,String archiveSha256) implements AutoCloseable { public void close() throws IOException {removeTree(staging,staging.getParent());} }

    public Result backup(Path data,Path library,Path destination,char[] password,boolean localSnapshot,Map<String,Object> configuration) throws Exception {
        BackupCipher.requirePassword(password);SafeLocalPaths.directory(destination);
        if(destination.toRealPath().startsWith(data.toRealPath()) || destination.toRealPath().startsWith(library.toRealPath()))throw new IOException("BACKUP_DESTINATION_OVERLAP");
        try(var directory=DesktopDataDirectory.open(data,false,library,false)) {
            var media=IndependentStorage.evaluate(data,library,destination);
            if(!localSnapshot && !media.independent())throw new IOException("BACKUP_INDEPENDENT_MEDIA_REQUIRED");
            return createLocked(directory,destination,password,media,configuration);
        }
    }
    Result createLocked(DesktopDataDirectory directory,Path destination,char[] password,IndependentStorage.Evidence media,Map<String,Object> configuration) throws Exception {
        SnapshotDatabase.validate(directory);
        String id=UUID.randomUUID().toString();Path work=privateDirectory(directory.root().getParent(),".coffer-backup-stage-");
        Path partial=destination.resolve(".coffer-backup-"+id+".partial"),output=destination.resolve("Coffer-"+Instant.now().toString().replace(':','-')+"-"+id+".cofferbackup");
        boolean published=false;
        try {
            Path h2=work.resolve("h2.zip");
            // H2's transactional BACKUP, not a live .mv.db filesystem copy.
            try(var connection=java.sql.DriverManager.getConnection(SnapshotDatabase.url(directory.root(),true),"sa","");var statement=connection.createStatement()) {
                statement.execute("BACKUP TO '"+h2.toString().replace("'","''")+"'");
            }
            DesktopDataDirectory.restrict(h2);
            var facts=SnapshotDatabase.facts(directory.root());int schema=SnapshotDatabase.schema(directory.root());
            var entries=new ArrayList<SnapshotManifest.Entry>();
            try(var file=FileChannel.open(partial,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                DesktopDataDirectory.restrict(partial);
                var channel=java.nio.channels.Channels.newOutputStream(file);
                // Closing AEAD must not close the file handle before force(true).
                var nonClosing=new FilterOutputStream(channel){@Override public void close() throws IOException {flush();}};
                try(var zip=new ZipOutputStream(BackupCipher.encrypt(nonClosing,password),java.nio.charset.StandardCharsets.UTF_8)) {
                    add(zip,h2,"database/h2.zip",entries);
                    addArea(zip,directory.root(),"data",true,entries);
                    addArea(zip,directory.libraryRoot(),"library",false,entries);
                    var captured=new HashMap<String,Object>(configuration==null?Map.of():configuration);captured.put("sourceZone",java.time.ZoneId.systemDefault().getId());
                    var manifest=new SnapshotManifest(1,id,Instant.now().toString(),SnapshotDatabase.h2Version(),schema,directory.identity(),directory.root().toString(),directory.libraryRoot().toString(),entries,facts,captured);
                    zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(JSON.writeValueAsBytes(manifest));zip.closeEntry();
                }
                file.force(true);
            }
            try(var verified=verify(partial,password,work)) {
                if(!verified.manifest().backupId().equals(id))throw new IOException("BACKUP_READBACK_MISMATCH");
                var report=result(verified.manifest(),output,SnapshotDatabase.sha256(partial),media.independent());
                // Same-directory move without replacement also works on removable media without hard links.
                Files.move(partial,output);published=true;
                return report;
            }
        } finally { if(!published)Files.deleteIfExists(partial);removeTree(work,work.getParent()); }
    }
    public Result inspect(Path archive,char[] password,Path localWorkParent) throws Exception {
        try(var verified=verify(archive,password,localWorkParent)){return result(verified.manifest(),archive,verified.archiveSha256(),false);}
    }
    Verified verify(Path archive,char[] password,Path parent) throws Exception {
        var archiveBefore=SafeLocalPaths.file(archive);String archiveHash=SnapshotDatabase.sha256(archive);SafeLocalPaths.directory(parent);Path work=privateDirectory(parent,".coffer-verify-");boolean valid=false;
        try {
            Path plain=work.resolve("authenticated.zip");
            try(var input=Files.newInputStream(archive,LinkOption.NOFOLLOW_LINKS);var out=FileChannel.open(plain,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                DesktopDataDirectory.restrict(plain);BackupCipher.decrypt(input,java.nio.channels.Channels.newOutputStream(out),password);out.force(true);
            }
            Path data=Files.createDirectory(work.resolve("dataset"));DesktopDataDirectory.restrict(data);Files.createDirectory(data.resolve("database"));
            SnapshotManifest manifest;var actual=new HashSet<String>();long total=0;
            try(var zip=new ZipFile(plain.toFile(),java.nio.charset.StandardCharsets.UTF_8)) {
                var definition=zip.getEntry("manifest.json");if(definition==null || definition.getSize()>32*1024*1024)throw new IOException("BACKUP_MANIFEST_MISSING");
                try(var input=zip.getInputStream(definition)){manifest=JSON.readValue(input,SnapshotManifest.class);}
                if(manifest.formatVersion()!=1 || !SnapshotDatabase.h2Version().equals(manifest.h2Version()) || manifest.entries()==null || manifest.entries().size()>MAX_ENTRIES)throw new IOException("BACKUP_FORMAT_OR_RUNTIME_MISMATCH");
                var expected=new HashMap<String,SnapshotManifest.Entry>();for(var entry:manifest.entries()){safeName(entry.path());if(entry.size()<0 || !entry.sha256().matches("[a-f0-9]{64}") || expected.put(entry.path(),entry)!=null)throw new IOException("BACKUP_MANIFEST_INVALID");}
                var files=zip.entries();int count=0;
                while(files.hasMoreElements()) {
                    var entry=files.nextElement();if(++count>MAX_ENTRIES+1)throw new IOException("BACKUP_ENTRY_LIMIT");safeName(entry.getName());
                    if(entry.isDirectory() || !actual.add(entry.getName()))throw new IOException("BACKUP_DUPLICATE_OR_DIRECTORY_ENTRY");
                    if(entry.getName().equals("manifest.json"))continue;
                    var fact=expected.get(entry.getName());if(fact==null)throw new IOException("BACKUP_UNDECLARED_ENTRY");
                    total=Math.addExact(total,fact.size());if(total>MAX_EXPANDED)throw new IOException("BACKUP_EXPANDED_LIMIT");
                    Path target=entry.getName().startsWith("data/")?data.resolve(entry.getName().substring(5))
                            :entry.getName().startsWith("library/")?data.resolve(entry.getName())
                            :entry.getName().equals("database/h2.zip")?work.resolve("h2.zip"):null;
                    if(target==null || !target.normalize().startsWith(work))throw new IOException("BACKUP_ENTRY_INVALID");
                    Files.createDirectories(target.getParent());
                    try(var input=zip.getInputStream(entry);var output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) {copyExact(input,output,fact.size());}
                    DesktopDataDirectory.restrict(target);
                    if(!SnapshotDatabase.sha256(target).equals(fact.sha256()))throw new IOException("BACKUP_FILE_HASH_MISMATCH");
                    Files.setLastModifiedTime(target,FileTime.from(Instant.parse(fact.modifiedTime())));
                }
                if(actual.size()!=expected.size()+1 || !actual.containsAll(expected.keySet()))throw new IOException("BACKUP_FILE_MISSING");
            }
            for(String required:List.of("data/"+DesktopDataDirectory.IDENTITY_FILE,"data/"+DesktopDataDirectory.KEY_FILE,"library/"+DesktopDataDirectory.LIBRARY_IDENTITY_FILE,"database/h2.zip"))
                if(!actual.contains(required))throw new IOException("BACKUP_REQUIRED_ENTRY_MISSING");
            try(var h2=new ZipFile(work.resolve("h2.zip").toFile())) {
                var iterator=h2.entries();if(!iterator.hasMoreElements())throw new IOException("BACKUP_DATABASE_MISSING");var entry=iterator.nextElement();
                if(!entry.getName().equals("coffer.mv.db") || iterator.hasMoreElements())throw new IOException("BACKUP_DATABASE_ARCHIVE_INVALID");
                try(var input=h2.getInputStream(entry);var output=Files.newOutputStream(data.resolve("database/coffer.mv.db"),StandardOpenOption.CREATE_NEW)){copyExact(input,output,entry.getSize());}
                DesktopDataDirectory.restrict(data.resolve("database/coffer.mv.db"));
            }
            try(var directory=DesktopDataDirectory.open(data,false)) {
                if(!directory.identity().equals(manifest.identity()))throw new IOException("BACKUP_IDENTITY_MISMATCH");SnapshotDatabase.validate(directory);
                if(!SnapshotDatabase.facts(data).equals(manifest.tables()) || SnapshotDatabase.schema(data)!=manifest.schemaVersion())throw new IOException("BACKUP_DATABASE_FACTS_MISMATCH");
            }
            var archiveAfter=SafeLocalPaths.file(archive);
            if(archiveBefore.size()!=archiveAfter.size() || !archiveBefore.lastModifiedTime().equals(archiveAfter.lastModifiedTime()) || !LocalImportSource.key(archiveBefore).equals(LocalImportSource.key(archiveAfter)) || !archiveHash.equals(SnapshotDatabase.sha256(archive)))throw new IOException("BACKUP_PACKAGE_CHANGED");
            Files.delete(plain);Files.delete(work.resolve("h2.zip"));valid=true;return new Verified(work,manifest,archiveHash);
        } finally {if(!valid)removeTree(work,parent);}
    }
    public Result restore(Path archive,char[] password,Path target) throws Exception {
        target=target.toAbsolutePath().normalize();Path parent=target.getParent();SafeLocalPaths.directory(parent);
        if(archive.toAbsolutePath().normalize().startsWith(target))throw new IOException("RESTORE_SOURCE_TARGET_OVERLAP");requireEmpty(target);
        try(var verified=verify(archive,password,parent)) {
            Path staged=verified.staging().resolve("dataset");SnapshotDatabase.relocate(staged,staged.resolve("library"),target.resolve("library"),verified.manifest());
            try(var directory=DesktopDataDirectory.open(staged,false)){SnapshotDatabase.validate(directory);}
            requireEmpty(target);if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))Files.delete(target);
            Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE);
            var report=result(verified.manifest(),archive,verified.archiveSha256(),false);
            return new Result("RESTORED_VERIFIED",report.packagePath(),report.packageSha256(),report.independent(),report.users(),report.files(),report.ledgerRows(),report.backupId());
        }
    }
    private static Result result(SnapshotManifest manifest,Path path,String hash,boolean independent) {
        long users=manifest.tables().get("APP_USER").rows(),files=manifest.tables().get("FILE_METADATA").rows();
        long ledgers=manifest.tables().entrySet().stream().filter(entry->entry.getKey().contains("INTENT") || entry.getKey().contains("TASK") || entry.getKey().startsWith("ARCHIVE_") || entry.getKey().equals("DESKTOP_WORK_COPY")).mapToLong(entry->entry.getValue().rows()).sum();
        return new Result(independent?"INDEPENDENT_BACKUP_VERIFIED":"LOCAL_SNAPSHOT_VERIFIED",path.toString(),hash,independent,users,files,ledgers,manifest.backupId());
    }
    private static void addArea(ZipOutputStream zip,Path root,String area,boolean data,List<SnapshotManifest.Entry> entries) throws Exception {
        var files=new ArrayList<Path>();Files.walkFileTree(root,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir,java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                SafeLocalPaths.directory(dir);Path relative=root.relativize(dir);
                if(data && !relative.toString().isEmpty() && Set.of("database","library","logs",".coffer-runtime",".upgrade-backups",".backup-staging").contains(relative.getName(0).toString()))return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                String name=file.getFileName().toString();if(name.equals(".coffer-desktop.lock") || name.equals(".coffer-library.lock"))return FileVisitResult.CONTINUE;
                if(name.startsWith("~$") || name.startsWith(".~lock."))throw new IOException("BACKUP_EXTERNAL_EDITOR_BUSY");
                SafeLocalPaths.file(file);files.add(file);if(files.size()>MAX_ENTRIES)throw new IOException("BACKUP_ENTRY_LIMIT");return FileVisitResult.CONTINUE;
            }
        });files.sort(Comparator.comparing(Path::toString));
        for(Path file:files)add(zip,file,area+"/"+root.relativize(file).toString().replace('\\','/'),entries);
    }
    private static void add(ZipOutputStream zip,Path file,String name,List<SnapshotManifest.Entry> entries) throws Exception {
        safeName(name);var before=SafeLocalPaths.file(file);MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(var input=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);var lock=input.tryLock(0,Long.MAX_VALUE,true)) {
            if(lock==null)throw new IOException("BACKUP_SOURCE_BUSY");zip.putNextEntry(new ZipEntry(name));var buffer=ByteBuffer.allocate(64*1024);int read;long size=0;
            while((read=input.read(buffer))!=-1){size+=read;digest.update(buffer.array(),0,read);zip.write(buffer.array(),0,read);buffer.clear();}zip.closeEntry();
            var after=SafeLocalPaths.file(file);if(size!=before.size() || after.size()!=before.size() || !after.lastModifiedTime().equals(before.lastModifiedTime()) || !LocalImportSource.key(after).equals(LocalImportSource.key(before)))throw new IOException("BACKUP_SOURCE_CHANGED");
        }
        String sha=HexFormat.of().formatHex(digest.digest());if(!SnapshotDatabase.sha256(file).equals(sha))throw new IOException("BACKUP_SOURCE_CHANGED");
        entries.add(new SnapshotManifest.Entry(name,before.size(),sha,before.lastModifiedTime().toInstant().toString()));
    }
    static Path privateDirectory(Path parent,String prefix) throws IOException {SafeLocalPaths.directory(parent);Path path=Files.createTempDirectory(parent,prefix);DesktopDataDirectory.restrict(path);return path;}
    static void requireEmpty(Path path) throws IOException {if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)){SafeLocalPaths.directory(path);try(var entries=Files.list(path)){if(entries.findAny().isPresent())throw new IOException("RESTORE_TARGET_NOT_EMPTY");}}}
    static void safeName(String name) throws IOException {if(name==null || name.isBlank() || name.length()>4096 || name.startsWith("/") || name.contains("\\") || name.contains(":") || name.indexOf('\0')>=0 || Arrays.stream(name.split("/",-1)).anyMatch(part->part.isEmpty() || part.equals(".") || part.equals("..")))throw new IOException("BACKUP_PATH_INVALID");}
    static void copyExact(InputStream input,OutputStream output,long expected) throws IOException {if(expected<0 || expected>MAX_EXPANDED)throw new IOException("BACKUP_ENTRY_SIZE_INVALID");byte[] buffer=new byte[64*1024];long size=0;int read;while((read=input.read(buffer))!=-1){size+=read;if(size>expected)throw new IOException("BACKUP_ENTRY_SIZE_MISMATCH");output.write(buffer,0,read);}if(size!=expected)throw new IOException("BACKUP_ENTRY_SIZE_MISMATCH");}
    static void removeTree(Path root,Path parent) throws IOException {
        root=root.toAbsolutePath().normalize();parent=parent.toAbsolutePath().normalize();if(root.equals(parent) || !root.startsWith(parent))throw new IOException("MAINTENANCE_CLEANUP_ESCAPE");
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;SafeLocalPaths.directory(root);
        try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList()){if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))SafeLocalPaths.directory(path);else SafeLocalPaths.file(path);Files.delete(path);}}
    }
}
