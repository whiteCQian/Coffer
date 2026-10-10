package com.coffer.deployment;

import com.coffer.config.MinioConfig;
import com.coffer.service.SecretCryptoService;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.zaxxer.hikari.HikariDataSource;
import io.minio.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Read-only recovery proof. Never starts Spring, Flyway, schedulers or tenant recovery jobs. */
public final class RecoveryPointMain {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static String secret(String name) throws IOException { return Files.readString(Path.of("/run/secrets/"+name)).trim(); }
    static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    static String hashStrings(List<String> values) { Collections.sort(values);return hash(String.join("\n",values).getBytes(StandardCharsets.UTF_8)); }
    static String value(Object value) throws Exception {
        if(value==null)return "null";
        if(value instanceof byte[] bytes)return "binary:"+hash(bytes)+":"+bytes.length;
        if(value instanceof Blob blob)try(var input=blob.getBinaryStream()){return "blob:"+streamHash(input)+":"+blob.length();}
        if(value instanceof Clob clob)return "clob:"+hash(clob.getSubString(1,Math.toIntExact(clob.length())).getBytes(StandardCharsets.UTF_8))+":"+clob.length();
        if(value instanceof java.math.BigDecimal number)return "decimal:"+number.stripTrailingZeros().toPlainString();
        return value.getClass().getName()+":"+value;
    }
    private static String streamHash(InputStream input)throws Exception{
        var digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int read;
        while((read=input.read(buffer))!=-1)digest.update(buffer,0,read);
        return HexFormat.of().formatHex(digest.digest());
    }
    private static HikariDataSource database()throws Exception{
        var source=new HikariDataSource();source.setJdbcUrl(System.getenv("COFFER_DB_URL"));source.setUsername("coffer_app");source.setPassword(secret("db_app_password"));
        source.setMaximumPoolSize(2); DatabaseTls.configure(source);return source;
    }
    private static MinioClient storage()throws Exception{
        var p=new MinioConfig.MinioProperties();p.setEndpoint(System.getenv("MINIO_ENDPOINT"));p.setSecure(true);p.setAccessKey(secret("minio_app_access"));p.setSecretKey(secret("minio_app_secret"));p.setCaCertificate("/run/secrets/minio_ca");
        return new MinioConfig().minioClient(p);
    }
    private static SecretCryptoService crypto()throws Exception{
        var environment=new org.springframework.core.env.StandardEnvironment();environment.setActiveProfiles("prod");
        var crypto=new SecretCryptoService(secret("master_key"),environment);crypto.initialize();return crypto;
    }
    private static ObjectNode capture()throws Exception{
        var result=JSON.createObjectNode(); result.put("formatVersion",2);result.put("capturedAt",Instant.now().toString());
        var tables=result.putObject("tables");var files=result.putArray("files");var credentials=new ArrayList<String>();var crypto=crypto();
        result.put("masterKeyId",crypto.currentKeyId());
        try(var source=database();var db=source.getConnection()){
            db.setReadOnly(true);db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);db.setAutoCommit(false);
            try(var query=db.createStatement();var rows=query.executeQuery("SELECT @@global.event_scheduler")){
                rows.next();if(!Set.of("OFF","DISABLED").contains(rows.getString(1)))throw new IllegalStateException("MYSQL_EVENT_WRITER_ACTIVE");
            }
            var names=new ArrayList<String>();
            try(var query=db.createStatement();var rows=query.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name")){
                while(rows.next()){String name=rows.getString(1);if(!name.matches("[a-zA-Z0-9_]+"))throw new IllegalStateException();names.add(name);}
            }
            for(String name:names){
                var hashes=new ArrayList<String>();var owners=new TreeMap<String,Long>();
                try(var query=db.createStatement();var rows=query.executeQuery("SELECT * FROM `"+name+"`")){
                    var meta=rows.getMetaData();int count=meta.getColumnCount();var columns=new TreeMap<String,Integer>();
                    for(int n=1;n<=count;n++)columns.put(meta.getColumnLabel(n).toLowerCase(Locale.ROOT),n);
                    while(rows.next()){
                        if(hashes.size()>=1000000)throw new IllegalStateException("TABLE_BASELINE_EXCEEDED");
                        var row=new TreeMap<String,String>();
                        for(var column:columns.entrySet()){
                            Object object=rows.getObject(column.getValue());row.put(column.getKey(),value(object));
                            if(column.getKey().startsWith("encrypted_")&&object!=null&&!object.toString().isBlank())
                                credentials.add(name+":"+column.getKey()+":"+hash(crypto.decrypt(object.toString()).getBytes(StandardCharsets.UTF_8)));
                        }
                        hashes.add(hash(JSON.writeValueAsBytes(row)));
                        if(columns.containsKey("owner_id"))owners.merge(String.valueOf(rows.getObject(columns.get("owner_id"))),1L,Long::sum);
                    }
                }
                var table=tables.putObject(name);table.put("rows",hashes.size());table.put("sha256",hashStrings(hashes));table.set("owners",JSON.valueToTree(owners));
            }
            try(var query=db.createStatement();var rows=query.executeQuery("SELECT id,owner_id,storage_path,file_size,content_sha256,revision FROM file_metadata ORDER BY id")){
                while(rows.next()){
                    long owner=rows.getLong("owner_id");String key=rows.getString("storage_path");
                    if(owner<=0||key==null||!key.startsWith("users/"+owner+"/"))throw new IllegalStateException("FILE_OWNERSHIP_INVALID");
                    var file=files.addObject();file.put("id",rows.getLong("id"));file.put("ownerId",owner);file.put("key",key);file.put("size",rows.getLong("file_size"));file.put("sha256",rows.getString("content_sha256"));file.put("revision",rows.getLong("revision"));
                }
            }
            db.commit();
        }
        result.put("authenticatedSecrets",credentials.size());result.put("decryptedSecretDigest",hashStrings(credentials));
        var objects=new ArrayList<ObjectNode>();var client=storage();String bucket=System.getenv("MINIO_BUCKET");
        for(var itemResult:client.listObjects(ListObjectsArgs.builder().bucket(bucket).recursive(true).includeVersions(true).build())){
            if(objects.size()>=1000000)throw new IllegalStateException("OBJECT_BASELINE_EXCEEDED");
            var item=itemResult.get();var object=JSON.createObjectNode();object.put("key",item.objectName());object.put("versionId",item.versionId());object.put("deleteMarker",item.isDeleteMarker());object.put("latest",item.isLatest());object.put("size",item.size());
            if(!item.isDeleteMarker())try(var stream=client.getObject(GetObjectArgs.builder().bucket(bucket).object(item.objectName()).versionId(item.versionId()).build())){
                object.put("sha256",streamHash(stream));
            }
            objects.add(object);
        }
        objects.sort(Comparator.comparing(o->o.path("key").asText()+":"+o.path("versionId").asText()));
        var list=result.putArray("objects");objects.forEach(list::add);
        for(var file:files){
            var object=objects.stream().filter(o->o.path("key").asText().equals(file.path("key").asText())&&o.path("latest").asBoolean()&&!o.path("deleteMarker").asBoolean()).findFirst().orElseThrow(()->new IllegalStateException("FILE_BODY_MISSING"));
            if(!object.path("sha256").equals(file.path("sha256"))||object.path("size").asLong()!=file.path("size").asLong())throw new IllegalStateException("FILE_BODY_FINGERPRINT_INVALID");
        }
        return result;
    }
    static void compare(JsonNode expected,JsonNode actual){
        // JDBC produces LongNode values, while JSON parses small integers as IntNode.
        // Normalize both through the wire format before structural comparison.
        try { expected=JSON.readTree(JSON.writeValueAsBytes(expected));actual=JSON.readTree(JSON.writeValueAsBytes(actual)); }
        catch(IOException malformed){throw new IllegalStateException("RECOVERY_MANIFEST_INVALID",malformed);}
        if(expected.path("formatVersion").asInt()!=2||actual.path("formatVersion").asInt()!=2)throw new IllegalStateException("RECOVERY_FORMAT_UNSUPPORTED");
        for(String key:List.of("tables","files","objects","masterKeyId","authenticatedSecrets","decryptedSecretDigest"))
            if(!expected.hasNonNull(key)||!actual.hasNonNull(key)||!Objects.equals(expected.get(key),actual.get(key)))throw new IllegalStateException("RECOVERY_POINT_MISMATCH_"+key.toUpperCase(Locale.ROOT));
    }
    private static void sanitize()throws Exception{
        try(var source=database();var db=source.getConnection();var query=db.createStatement()){
            db.setAutoCommit(false);
            query.executeUpdate("DELETE FROM SPRING_SESSION_ATTRIBUTES");query.executeUpdate("DELETE FROM SPRING_SESSION");
            query.executeUpdate("UPDATE file_metadata SET vector_indexed_at=NULL,vector_index_generation=NULL");
            // Old Redis generations are never trusted after restoring an empty cache.
            db.commit();
        }
    }
    public static void main(String[] args){
        try{
            if(args.length<1)throw new IllegalArgumentException();
            if(args[0].equals("sanitize")){sanitize();System.out.println("RECOVERY_SESSIONS_REVOKED_VECTORS_INVALIDATED");System.exit(0);return;}
            var point=capture();Path file=Path.of(args[1]);
            if(args[0].equals("capture")){Files.writeString(file,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(point),StandardOpenOption.CREATE_NEW);System.out.println("RECOVERY_POINT_CAPTURED");}
            else if(args[0].equals("verify")){compare(JSON.readTree(Files.readString(file)),point);System.out.println("RECOVERY_POINT_VERIFIED");}
            else throw new IllegalArgumentException();
            // The standalone process owns SDK executor threads; do not leave its volume mounted
            // while waiting for the MinIO HTTP connection pool's idle timeout.
            System.exit(0);
        }catch(Exception failure){
            String reason=failure.getMessage();
            if(reason==null||!reason.matches("[A-Z_]{3,80}"))reason=failure.getClass().getSimpleName();
            System.err.println("RECOVERY_OPERATION_FAILED: "+reason);System.exit(1);
        }
    }
}
