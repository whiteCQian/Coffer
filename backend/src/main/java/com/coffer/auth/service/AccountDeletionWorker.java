package com.coffer.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j @Component @Profile("!desktop") @RequiredArgsConstructor
public class AccountDeletionWorker {
    private final JdbcTemplate jdbc;
    private final ObjectProvider<AccountObjectPurger> objects;
    private final AccountCachePurger caches;
    private final AccountDatabasePurger database;
    private final PlatformTransactionManager transactions;
    @org.springframework.beans.factory.annotation.Value("${coffer.recovery.hold:false}") private boolean recoveryHold;
    @EventListener(ApplicationReadyEvent.class) public void recover() {
        if(recoveryHold)return;
        jdbc.update("UPDATE account_deletion_job SET status='QUEUED',next_attempt_at=CURRENT_TIMESTAMP WHERE status='RUNNING'");
        jdbc.update("UPDATE app_user SET deletion_status='QUEUED' WHERE deletion_status='RUNNING'");
    }
    @Scheduled(fixedDelayString="${coffer.account-deletion.delay-ms:3000}",initialDelayString="${coffer.account-deletion.initial-delay-ms:3000}")
    public void tick() {
        if(recoveryHold)return;
        for(var target:jdbc.queryForList("SELECT target_user_id FROM account_deletion_job WHERE status IN ('QUEUED','FAILED') AND attempts<5 AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY created_at LIMIT 10",Long.class))
            process(target);
    }
    public void process(long target) {
        Boolean claimed=new TransactionTemplate(transactions).execute(status -> {
            if(jdbc.update("UPDATE account_deletion_job SET status='RUNNING',attempts=attempts+1,updated_at=CURRENT_TIMESTAMP WHERE target_user_id=? AND status IN ('QUEUED','FAILED') AND attempts<5 AND next_attempt_at<=CURRENT_TIMESTAMP",target)!=1)return false;
            if(jdbc.update("UPDATE app_user SET deletion_status='RUNNING',updated_at=CURRENT_TIMESTAMP WHERE id=? AND enabled=FALSE AND deletion_status IN ('QUEUED','FAILED')",target)!=1)
                throw new IllegalStateException("账号删除状态不一致");
            return true;
        });
        if(!Boolean.TRUE.equals(claimed))return;
        try(var gate=PrivateWorkspaceGate.erase(target)) {
            var purger=objects.getIfAvailable(); if(purger==null)throw new IllegalStateException("账号存储清理未配置");
            purger.purge(target); caches.purge(target); database.purge(target);
        } catch(AuthFailureException busy) {
            jdbc.update("UPDATE account_deletion_job SET status='QUEUED',attempts=attempts-1,next_attempt_at=?,updated_at=CURRENT_TIMESTAMP WHERE target_user_id=? AND status='RUNNING'",java.time.LocalDateTime.now().plusSeconds(3),target);
            jdbc.update("UPDATE app_user SET deletion_status='QUEUED' WHERE id=? AND deletion_status='RUNNING'",target);
        } catch(Exception failure) {
            jdbc.update("UPDATE account_deletion_job SET status='FAILED',next_attempt_at=?,updated_at=CURRENT_TIMESTAMP WHERE target_user_id=? AND status='RUNNING'",java.time.LocalDateTime.now().plusSeconds(30),target);
            jdbc.update("UPDATE app_user SET deletion_status='FAILED' WHERE id=? AND deletion_status='RUNNING'",target);
            log.warn("账号清理未完成，可重试，异常类型={}",failure.getClass().getSimpleName());
        }
    }
}
