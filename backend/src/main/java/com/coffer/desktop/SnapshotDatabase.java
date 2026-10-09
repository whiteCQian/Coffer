package com.coffer.desktop;

import com.coffer.auth.service.TenantContext;
import com.coffer.file.infrastructure.storage.LocalFileStorageAdapter;
import com.coffer.file.infrastructure.storage.SafeLocalPaths;
import com.coffer.file.application.LocalImportSource;
import com.coffer.service.SecretCryptoService;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Offline, owner-aware verification without starting workers, migrating or sending model content. */
final class SnapshotDatabase {
    static String h2Version() throws Exception {return (String)Class.forName("org.h2.engine.Constants").getField("VERSION").get(null);}
    static String url(Path data,boolean writable){return "jdbc:h2:file:"+data.resolve("database/coffer").toString().replace('\\','/')+";IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE"+(writable?"":";ACCESS_MODE_DATA=r");}
    static Map<String,SnapshotManifest.TableFact> facts(Path data) throws Exception {
        var result=new TreeMap<String,SnapshotManifest.TableFact>();
        try(var connection=DriverManager.getConnection(url(data,false),"sa","");var tables=connection.createStatement().executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")){
            while(tables.next()){
                String table=tables.getString(1);var keys=new TreeMap<Short,String>();
                try(var primary=connection.getMetaData().getPrimaryKeys(null,"PUBLIC",table)){while(primary.next())keys.put(primary.getShort("KEY_SEQ"),primary.getString("COLUMN_NAME"));}
                if(keys.isEmpty())throw new IOException("BACKUP_TABLE_WITHOUT_IDENTITY");
                MessageDigest hash=MessageDigest.getInstance("SHA-256");long rows=0;
                try(var query=connection.createStatement();var records=query.executeQuery("SELECT * FROM "+quoted(table)+" ORDER BY "+keys.values().stream().map(SnapshotDatabase::quoted).collect(java.util.stream.Collectors.joining(",")))){
                    var metadata=records.getMetaData();
                    for(int column=1;column<=metadata.getColumnCount();column++) {
                        value(hash,metadata.getColumnName(column));value(hash,metadata.getColumnTypeName(column));
                        value(hash,Integer.toString(metadata.getPrecision(column)));value(hash,Integer.toString(metadata.getScale(column)));
                    }
                    while(records.next()){
                        rows++;hash.update((byte)0x7f);
                        for(int column=1;column<=metadata.getColumnCount();column++){
                            value(hash,metadata.getColumnName(column));Object object=records.getObject(column);
                            if(object instanceof Blob blob){hash.update((byte)1);try(var input=blob.getBinaryStream()){stream(hash,input);}blob.free();}
                            else if(object instanceof Clob clob){hash.update((byte)2);try(var reader=clob.getCharacterStream()){char[] chars=new char[8192];int count;while((count=reader.read(chars))!=-1)hash.update(new String(chars,0,count).getBytes(StandardCharsets.UTF_8));}clob.free();}
                            else if(object instanceof byte[] bytes){hash.update((byte)3);hash.update(bytes);}
                            else value(hash,object==null?null:object instanceof java.sql.Timestamp time?time.toLocalDateTime().toString():object.toString());
                            hash.update((byte)0xff);
                        }
                    }
                }
                result.put(table,new SnapshotManifest.TableFact(rows,HexFormat.of().formatHex(hash.digest())));
            }
        }
        if(!result.containsKey("APP_USER") || !result.containsKey("FILE_METADATA") || !result.containsKey("DESKTOP_LIBRARY_BINDING"))throw new IOException("BACKUP_DATABASE_INCOMPLETE");
        return result;
    }
    static int schema(Path data) throws Exception {
        try(var connection=DriverManager.getConnection(url(data,false),"sa","");var rows=connection.createStatement().executeQuery("SELECT \"version\" FROM \"flyway_schema_history\" WHERE \"success\"=true AND \"version\" IS NOT NULL ORDER BY \"installed_rank\" DESC LIMIT 1")) {
            if(!rows.next())throw new IOException("BACKUP_SCHEMA_MISSING");return Integer.parseInt(rows.getString(1));
        }
    }
    static void validate(DesktopDataDirectory directory) throws Exception {
        var crypto=new SecretCryptoService(directory.masterKey(),new StandardEnvironment());crypto.initialize();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url(directory.root(),false),"sa",""));DesktopDataConfiguration.verifyBinding(jdbc,directory,crypto,false);
        var storage=new LocalFileStorageAdapter(directory.libraryRoot().toString());
        var users=new HashSet<Long>(jdbc.queryForList("SELECT id FROM app_user",Long.class));
        for(var file:jdbc.queryForList("SELECT owner_id,storage_path,file_size,content_sha256 FROM file_metadata")){
            long owner=((Number)file.get("owner_id")).longValue();if(!users.contains(owner))throw new IOException("BACKUP_OWNER_INVALID");
            requireObject(storage,owner,(String)file.get("storage_path"),((Number)file.get("file_size")).longValue(),(String)file.get("content_sha256"));
        }
        if(table(jdbc,"WORK_SAVE_INTENT"))for(var row:jdbc.queryForList("SELECT owner_id,target_key,target_size,request_sha256 FROM work_save_intent WHERE status='OBJECT_WRITTEN'"))
            requireObject(storage,((Number)row.get("owner_id")).longValue(),(String)row.get("target_key"),((Number)row.get("target_size")).longValue(),(String)row.get("request_sha256"));
        if(table(jdbc,"FILE_WRITE_INTENT"))for(var row:jdbc.queryForList("SELECT owner_id,object_key,declared_size,content_sha256 FROM file_write_intent WHERE status='OBJECT_WRITTEN'"))
            requireObject(storage,((Number)row.get("owner_id")).longValue(),(String)row.get("object_key"),((Number)row.get("declared_size")).longValue(),(String)row.get("content_sha256"));
        if(table(jdbc,"DESKTOP_WORK_COPY"))for(var row:jdbc.queryForList("SELECT owner_id,work_key FROM desktop_work_copy WHERE status IN ('READY','OPENED','SAVED','SAVED_AS','KEPT','NEEDS_DECISION','SAVING','SAVING_AS')")) {
            long owner=((Number)row.get("owner_id")).longValue();String key=(String)row.get("work_key");
            TenantContext.runAs(owner,()->{com.coffer.file.storage.StorageKey.requireOwned(key);try{SafeLocalPaths.file(directory.libraryRoot().resolve(key));}catch(IOException missing){throw new IllegalStateException("BACKUP_WORK_FILE_MISSING");}});
        }
        // Every persisted encrypted configuration must be recoverable with the included original master key.
        try(var connection=DriverManager.getConnection(url(directory.root(),false),"sa","");var columns=connection.getMetaData().getColumns(null,"PUBLIC",null,null)) {
            while(columns.next()){
                String column=columns.getString("COLUMN_NAME"),name=columns.getString("TABLE_NAME");
                if(!column.toLowerCase(Locale.ROOT).startsWith("encrypted_"))continue;
                try(var query=connection.createStatement();var values=query.executeQuery("SELECT "+quoted(column)+" FROM "+quoted(name)+" WHERE "+quoted(column)+" IS NOT NULL")){
                    while(values.next()){String encoded=values.getString(1);if(!encoded.isBlank())crypto.decrypt(encoded);}
                }
            }
        }
    }
    private static void requireObject(LocalFileStorageAdapter storage,long owner,String key,long size,String sha) throws Exception {
        if(sha==null || !sha.matches("[a-f0-9]{64}"))throw new IOException("BACKUP_FILE_IDENTITY_MISSING");
        var object=TenantContext.supplyAs(owner,()->storage.stat(key));if(object.size()!=size || !object.sha256().equals(sha))throw new IOException("BACKUP_FILE_IDENTITY_MISMATCH");
    }
    static void relocate(Path data,Path stagedLibrary,Path finalLibrary,SnapshotManifest manifest) throws Exception {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url(data,true),"sa",""));
        jdbc.update("UPDATE desktop_library_binding SET library_root=? WHERE id=1",finalLibrary.toString());
        var rebound=new HashSet<String>();
        if(table(jdbc,"INBOX_IMPORT_RECORD"))for(var row:jdbc.queryForList("SELECT * FROM inbox_import_record ORDER BY id DESC")) {
            long owner=((Number)row.get("owner_id")).longValue();String name=(String)row.get("source_file_name");
            String logical="users/"+owner+"/inbox/"+name;TenantContext.runAs(owner,()->com.coffer.file.storage.StorageKey.requireOwned(logical));
            Path file=stagedLibrary.resolve(logical);String newPath=finalLibrary.resolve(logical).toString();
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)) {
                var attrs=SafeLocalPaths.file(file);String sha=sha256(file),key=LocalImportSource.key(attrs);
                String snapshot=sha256((name+"|"+attrs.size()+"|"+attrs.lastModifiedTime().toMillis()+"|"+key+"|"+sha).getBytes(StandardCharsets.UTF_8));
                // Historical snapshots remain historical; only the exact copied source may adopt its new machine identity.
                var oldTime=((java.sql.Timestamp)row.get("source_modified_at")).toLocalDateTime().atZone(ZoneId.of((String)manifest.configuration().getOrDefault("sourceZone",ZoneId.systemDefault().getId()))).toInstant();
                if(!rebound.contains(logical) && oldTime.toEpochMilli()==attrs.lastModifiedTime().toMillis() && (Objects.equals(row.get("content_sha256"),sha) || row.get("content_sha256")==null && ((Number)row.get("source_size")).longValue()==attrs.size())) {
                    jdbc.update("UPDATE inbox_import_record SET source_path=?,source_file_key=?,source_modified_at=?,snapshot_key=? WHERE id=?",newPath,key,LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(),ZoneId.systemDefault()),snapshot,row.get("id"));
                    rebound.add(logical);
                } else jdbc.update("UPDATE inbox_import_record SET source_path=? WHERE id=?",newPath,row.get("id"));
            } else jdbc.update("UPDATE inbox_import_record SET source_path=? WHERE id=?",newPath,row.get("id"));
        }
        for(String name:List.of("WORK_SAVE_INTENT","DESKTOP_WORK_COPY"))if(table(jdbc,name) && jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME=? AND COLUMN_NAME='BEFORE_FILE_KEY'",Integer.class,name)>0)for(var row:jdbc.queryForList("SELECT * FROM "+quoted(name))) {
            var current=jdbc.queryForList("SELECT storage_path,revision,content_sha256,file_size FROM file_metadata WHERE id=? AND owner_id=?",row.get("file_id"),row.get("owner_id"));
            if(current.size()!=1)continue;var file=current.get(0);
            if(!Objects.equals(file.get("storage_path"),row.get("before_key")) || !Objects.equals(file.get("content_sha256"),row.get("before_sha256"))
                    || ((Number)file.get("revision")).longValue()!=((Number)row.get("expected_revision")).longValue())continue;
            Path body=stagedLibrary.resolve((String)row.get("before_key"));var attrs=SafeLocalPaths.file(body);
            jdbc.update("UPDATE "+quoted(name)+" SET before_file_key=?,before_modified_time=? WHERE id=?",LocalImportSource.key(attrs),attrs.lastModifiedTime().toString(),row.get("id"));
        }
        // Browser sessions are machine-local authorizations; restored accounts must log in again.
        if(table(jdbc,"SPRING_SESSION_ATTRIBUTES"))jdbc.update("DELETE FROM SPRING_SESSION_ATTRIBUTES");
        if(table(jdbc,"SPRING_SESSION"))jdbc.update("DELETE FROM SPRING_SESSION");
    }
    static boolean table(JdbcTemplate jdbc,String name){return jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME=?",Integer.class,name)>0;}
    static String sha256(Path file) throws Exception {var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){stream(digest,input);}return HexFormat.of().formatHex(digest.digest());}
    static String sha256(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void stream(MessageDigest hash,InputStream input) throws IOException {byte[] bytes=new byte[64*1024];int count;while((count=input.read(bytes))!=-1)hash.update(bytes,0,count);}
    private static void value(MessageDigest hash,String value){if(value==null){hash.update((byte)0);return;}byte[] bytes=value.getBytes(StandardCharsets.UTF_8);hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());hash.update(bytes);}
    private static String quoted(String value){return '"'+value.replace("\"","\"\"")+'"';}
}
