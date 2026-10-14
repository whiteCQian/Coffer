package com.coffer.auth.service;

import com.coffer.auth.infrastructure.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service @RequiredArgsConstructor
public class AccountDeletionService {
    private final AdminAuthorization admin;
    private final AppUserRepository users;
    private final JdbcTemplate jdbc;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    public record Request(String confirmation, boolean deleteData) { }
    public record View(long targetUserId, String status) { }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public View request(long targetId, Request request) {
        admin.requireAdmin();
        jdbc.queryForObject("SELECT id FROM auth_setup_lock WHERE id=1 FOR UPDATE", Long.class);
        admin.requireAdmin(); // A competing delete may have revoked this administrator while it waited.
        var actor = (AuthPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (actor.id().equals(targetId)) throw new AuthFailureException(HttpStatus.CONFLICT, "不能删除当前登录账号");
        var target = users.lockById(targetId).orElseThrow(() -> new AuthFailureException(HttpStatus.NOT_FOUND, "账号不存在"));
        if (request == null || !request.deleteData() || !target.getUsername().equals(request.confirmation()))
            throw new AuthFailureException(HttpStatus.BAD_REQUEST, "请输入完整账号名，确认删除账号及关联数据");
        if (target.getRole() == com.coffer.auth.domain.AuthRole.ADMIN
                && jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE role='ADMIN' AND enabled=TRUE AND deletion_status IS NULL AND id<>?", Long.class, targetId) == 0)
            throw new AuthFailureException(HttpStatus.CONFLICT, "必须保留一个可用管理员账号");
        if (target.getDeletionStatus() != null) return new View(targetId, target.getDeletionStatus());
        target.setEnabled(false); target.setDeletionStatus("QUEUED");
        target.setPasswordHash("!account-deletion-requested"); target.setUpdatedAt(java.time.LocalDateTime.now()); users.saveAndFlush(target);
        sessions.findByPrincipalName(target.getUsername()).values().forEach(s -> sessions.deleteById(s.getId()));
        jdbc.update("INSERT INTO account_deletion_job(target_user_id,actor_user_id,actor_username,status,attempts,next_attempt_at,created_at,updated_at) VALUES(?,?,?,'QUEUED',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                targetId, actor.id(), actor.getUsername());
        jdbc.update("INSERT INTO account_audit_log(actor_user_id,actor_username,target_user_id,action,created_at) VALUES(?,?,?,'ACCOUNT_DELETION_REQUESTED',CURRENT_TIMESTAMP)",
                actor.id(), actor.getUsername(), targetId);
        return new View(targetId,"QUEUED");
    }
    @Transactional
    public View retry(long targetId) {
        admin.requireAdmin();
        if(jdbc.queryForList("SELECT target_user_id FROM account_deletion_job WHERE target_user_id=? FOR UPDATE",Long.class,targetId).isEmpty())
            throw new AuthFailureException(HttpStatus.CONFLICT,"账号没有失败的删除任务");
        var target = users.lockById(targetId).orElseThrow(() -> new AuthFailureException(HttpStatus.NOT_FOUND,"账号不存在"));
        if (!"FAILED".equals(target.getDeletionStatus())) throw new AuthFailureException(HttpStatus.CONFLICT,"账号没有失败的删除任务");
        jdbc.update("UPDATE account_deletion_job SET status='QUEUED',attempts=0,next_attempt_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE target_user_id=? AND status='FAILED'",targetId);
        target.setEnabled(false); target.setDeletionStatus("QUEUED"); users.save(target);
        return new View(targetId,"QUEUED");
    }
}
