package com.coffer.auth.service;

import com.coffer.auth.domain.AccountAuditLog;
import com.coffer.auth.domain.AppUser;
import com.coffer.auth.domain.AuthRole;
import com.coffer.auth.infrastructure.AccountAuditLogRepository;
import com.coffer.auth.infrastructure.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AppUserRepository users;
    private final AccountAuditLogRepository auditLogs;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    @Value("${coffer.auth.initial-admin-token:}")
    private String initialAdminToken;

    @Transactional(readOnly = true)
    public boolean isSetupRequired() {
        return users.count() == 0;
    }

    public boolean isSetupAvailable() {
        return initialAdminToken != null && !initialAdminToken.isBlank();
    }

    @Transactional
    public AppUser createInitialAdmin(String suppliedToken, String username, String password) {
        if (!isSetupAvailable()) {
            throw new AuthFailureException(HttpStatus.SERVICE_UNAVAILABLE,
                    "首次管理员初始化尚未配置");
        }
        // Serialize simultaneous first-run requests so exactly one administrator can be created.
        jdbcTemplate.queryForObject("SELECT id FROM auth_setup_lock WHERE id = 1 FOR UPDATE", Long.class);
        if (users.count() != 0) {
            throw new AuthFailureException(HttpStatus.CONFLICT, "首次初始化已完成");
        }
        if (!constantTimeEquals(initialAdminToken, suppliedToken)) {
            throw new AuthFailureException(HttpStatus.UNAUTHORIZED, "初始化凭据无效");
        }
        validatePassword(password);
        AppUser admin = users.saveAndFlush(new AppUser(normalizeUsername(username),
                passwordEncoder.encode(password), AuthRole.ADMIN));
        auditLogs.save(new AccountAuditLog(admin.getId(), admin.getUsername(), admin.getId(),
                "INITIAL_ADMIN_CREATED"));
        return admin;
    }

    @Transactional
    public AppUser createUser(AuthPrincipal actor, String username, String password) {
        requireAdmin(actor);
        validatePassword(password);
        String normalized = normalizeUsername(username);
        if (users.existsByUsername(normalized)) {
            throw new AuthFailureException(HttpStatus.CONFLICT, "该账号名不可用");
        }
        AppUser created = users.saveAndFlush(new AppUser(normalized, passwordEncoder.encode(password), AuthRole.USER));
        auditLogs.save(new AccountAuditLog(actor.id(), actor.getUsername(), created.getId(), "USER_CREATED"));
        return created;
    }

    @Transactional
    public void disableUser(AuthPrincipal actor, Long targetId) {
        requireAdmin(actor);
        AppUser target = requireUser(targetId);
        if (target.getRole() != AuthRole.USER) {
            throw new AuthFailureException(HttpStatus.BAD_REQUEST, "不能停用管理员账号");
        }
        if (!target.isEnabled()) {
            return;
        }
        target.setEnabled(false);
        target.setUpdatedAt(java.time.LocalDateTime.now());
        users.save(target);
        revokeSessions(target.getUsername());
        auditLogs.save(new AccountAuditLog(actor.id(), actor.getUsername(), target.getId(), "USER_DISABLED"));
    }

    @Transactional
    public void enableUser(AuthPrincipal actor, Long targetId) {
        requireAdmin(actor);
        AppUser target = requireUser(targetId);
        if (target.getRole() != AuthRole.USER) {
            throw new AuthFailureException(HttpStatus.BAD_REQUEST, "不能通过此操作启用管理员账号");
        }
        if (target.isEnabled()) return;
        target.setEnabled(true);
        target.setUpdatedAt(java.time.LocalDateTime.now());
        users.save(target);
        auditLogs.save(new AccountAuditLog(actor.id(), actor.getUsername(), target.getId(), "USER_ENABLED"));
    }

    @Transactional
    public void resetPassword(AuthPrincipal actor, Long targetId, String newPassword) {
        requireAdmin(actor);
        validatePassword(newPassword);
        AppUser target = requireUser(targetId);
        if (target.getRole() != AuthRole.USER) {
            throw new AuthFailureException(HttpStatus.BAD_REQUEST, "不能通过此操作重置管理员凭据");
        }
        target.setPasswordHash(passwordEncoder.encode(newPassword));
        target.setUpdatedAt(java.time.LocalDateTime.now());
        users.save(target);
        revokeSessions(target.getUsername());
        auditLogs.save(new AccountAuditLog(actor.id(), actor.getUsername(), target.getId(), "USER_PASSWORD_RESET"));
    }

    @Transactional
    public void changeOwnPassword(AuthPrincipal actor, String currentPassword,
                                  String newPassword, String currentSessionId) {
        requireAuthenticatedActor(actor);
        validatePassword(newPassword);
        AppUser user = requireUser(actor.id());
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new AuthFailureException(HttpStatus.BAD_REQUEST, "当前密码不正确");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new AuthFailureException(HttpStatus.BAD_REQUEST, "新密码必须与当前密码不同");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(java.time.LocalDateTime.now());
        users.save(user);
        revokeSessionsExcept(user.getUsername(), currentSessionId);
        auditLogs.save(new AccountAuditLog(actor.id(), actor.getUsername(), actor.id(), "PASSWORD_CHANGED"));
    }

    @Transactional(readOnly = true)
    public List<AppUser> listUsers() {
        requireAdmin(null);
        return users.findAll(Sort.by(Sort.Direction.ASC, "username"));
    }

    @Transactional(readOnly = true)
    public List<AccountAuditLog> listRecentAudit() {
        requireAdmin(null);
        return auditLogs.findTop100ByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public java.time.LocalDateTime getCreatedAt(Long id) {
        return requireUser(id).getCreatedAt();
    }

    private AuthPrincipal requireAuthenticatedActor(AuthPrincipal supplied) {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthPrincipal principal)
                || (supplied != null && !principal.id().equals(supplied.id()))
                || !users.existsByIdAndRoleAndEnabledTrue(principal.id(), principal.role())) {
            throw new org.springframework.security.access.AccessDeniedException("无权执行此操作");
        }
        return principal;
    }

    private void requireAdmin(AuthPrincipal supplied) {
        if (requireAuthenticatedActor(supplied).role() != AuthRole.ADMIN) {
            throw new org.springframework.security.access.AccessDeniedException("无权执行此操作");
        }
    }

    private AppUser requireUser(Long id) {
        return users.findById(id)
                .orElseThrow(() -> new AuthFailureException(HttpStatus.NOT_FOUND, "账号不存在"));
    }

    private void revokeSessions(String username) {
        sessions.findByPrincipalName(username).values()
                .forEach(session -> sessions.deleteById(session.getId()));
    }

    private void revokeSessionsExcept(String username, String keepSessionId) {
        sessions.findByPrincipalName(username).values().stream()
                .filter(session -> !session.getId().equals(keepSessionId))
                .forEach(session -> sessions.deleteById(session.getId()));
    }

    private String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private void validatePassword(String password) {
        int byteLength = password.getBytes(StandardCharsets.UTF_8).length;
        if (password.length() < 12 || byteLength > 72) {
            throw new AuthFailureException(HttpStatus.BAD_REQUEST,
                    "密码长度须至少 12 个字符且不超过 72 个 UTF-8 字节");
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
