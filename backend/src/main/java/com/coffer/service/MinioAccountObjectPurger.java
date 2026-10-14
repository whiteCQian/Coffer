package com.coffer.service;

import com.coffer.auth.service.AccountObjectPurger;
import com.coffer.config.MinioConfig;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Dedicated blind purge: only an authorized running deletion can remove this exact owner prefix. */
@Component @Profile("!desktop") @RequiredArgsConstructor
public class MinioAccountObjectPurger implements AccountObjectPurger {
    private final MinioClient client;
    private final MinioConfig.MinioProperties properties;
    private final JdbcTemplate jdbc;
    @Override public void purge(long targetUserId) {
        if(targetUserId<=0 || jdbc.queryForObject("SELECT COUNT(*) FROM app_user u JOIN account_deletion_job j ON j.target_user_id=u.id WHERE u.id=? AND u.enabled=FALSE AND u.deletion_status='RUNNING' AND j.status='RUNNING'",Long.class,targetUserId)!=1)
            throw new org.springframework.security.access.AccessDeniedException("账号删除未授权");
        String prefix="users/" + targetUserId + "/";
        String bucket=properties.getBucketName();
        if(bucket==null||bucket.isBlank())throw new IllegalStateException("账号文件存储不可用");
        try {
            var args=ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).includeVersions(true).build();
            for(var result:client.listObjects(args)) {
                var item=result.get();
                if(!item.objectName().startsWith(prefix))throw new IllegalStateException("账号存储范围不匹配");
                client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(item.objectName()).versionId(item.versionId()).build());
            }
            if(client.listObjects(args).iterator().hasNext())throw new IllegalStateException("仍有账号存储版本待清理");
        } catch(Exception failure) { throw new IllegalStateException("账号文件清理未完成"); }
    }
}
