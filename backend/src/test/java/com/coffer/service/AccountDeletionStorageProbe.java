package com.coffer.service;

import com.coffer.config.MinioConfig;
import io.minio.*;
import java.nio.file.*;
import java.io.ByteArrayInputStream;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Isolated synthetic-account fixture; caller verifies both new account identities before invocation. */
public class AccountDeletionStorageProbe {
    public static void main(String[] args) throws Exception {
        String mode=args[0]; long a=Long.parseLong(args[1]),b=Long.parseLong(args[2]);
        if(a<=0||b<=0||a==b)throw new IllegalArgumentException("Invalid fixture owners");
        var props=new MinioConfig.MinioProperties(); props.setEndpoint(System.getenv("MINIO_ENDPOINT")); props.setSecure(true);
        props.setAccessKey(Files.readString(Path.of("/run/secrets/minio_app_access")).trim());
        props.setSecretKey(Files.readString(Path.of("/run/secrets/minio_app_secret")).trim());
        props.setBucketName(System.getenv("MINIO_BUCKET")); props.setCaCertificate(System.getenv("MINIO_CA_CERTIFICATE"));
        var client=new MinioConfig().minioClient(props); String bucket=props.getBucketName();
        var connection=new LettuceConnectionFactory("redis",6379); connection.afterPropertiesSet(); connection.start();
        var redis=new StringRedisTemplate(connection); redis.afterPropertiesSet();
        String ka="users/"+a+"/objects/deletion-fixture.txt", kb="users/"+b+"/objects/control-fixture.txt";
        try {
            if("seed".equals(mode)) {
                if(versions(client,bucket,a)!=0||versions(client,bucket,b)!=0)throw new AssertionError("Fixture namespaces must start empty");
                for(int n=0;n<2;n++)write(client,bucket,ka,"synthetic deleted-account version "+n);
                client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(ka).build());
                write(client,bucket,kb,"synthetic other-account control");
                for(long id:new long[]{a,b}) {
                    redis.opsForValue().set("coffer:vector:owner:"+id+":account-deletion-fixture","synthetic vector cache");
                    redis.opsForValue().set("chat:memory:v2:"+id+":account-deletion-fixture","synthetic conversation cache");
                }
                if(versions(client,bucket,a)<3)throw new AssertionError("Historical versions/delete marker fixture missing");
                System.out.println("PASS: isolated history and cache fixtures seeded");
            } else if("verify-target".equals(mode)) {
                if(versions(client,bucket,a)!=0||versions(client,bucket,b)!=1)throw new AssertionError("Object purge isolation failed");
                if(redis.hasKey("coffer:vector:owner:"+a+":account-deletion-fixture")||redis.hasKey("chat:memory:v2:"+a+":account-deletion-fixture"))throw new AssertionError("Deleted account cache remains");
                if(!redis.hasKey("coffer:vector:owner:"+b+":account-deletion-fixture")||!redis.hasKey("chat:memory:v2:"+b+":account-deletion-fixture"))throw new AssertionError("Other account cache changed");
                System.out.println("PASS: all deleted-owner versions/markers/caches removed; control owner preserved");
            } else if("verify-clean".equals(mode)) {
                if(versions(client,bucket,a)!=0||versions(client,bucket,b)!=0)throw new AssertionError("Fixture object cleanup incomplete");
                if(redis.hasKey("coffer:vector:owner:"+b+":account-deletion-fixture")||redis.hasKey("chat:memory:v2:"+b+":account-deletion-fixture"))throw new AssertionError("Fixture cache cleanup incomplete");
                System.out.println("PASS: both disposable namespaces fully cleaned");
            } else throw new IllegalArgumentException("Unknown fixture action");
        } finally { connection.destroy(); }
    }
    static void write(MinioClient client,String bucket,String key,String value) throws Exception {
        byte[] body=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(new ByteArrayInputStream(body),body.length,-1).contentType("text/plain").build());
    }
    static int versions(MinioClient client,String bucket,long owner) throws Exception {
        int count=0; String prefix="users/"+owner+"/";
        for(var item:client.listObjects(ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).includeVersions(true).build())) {
            if(!item.get().objectName().startsWith(prefix))throw new AssertionError("Unexpected namespace"); count++;
        }
        return count;
    }
}
