package com.coffer.auth.service;

import com.coffer.auth.domain.AuthRole;
import com.coffer.auth.infrastructure.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OwnerAuthorization {
    private final AppUserRepository users;
    private final jakarta.persistence.EntityManagerFactory entityManagerFactory;

    public Long requireOwner() {
        Long owner = TenantContext.requireOwnerId();
        Object resource = org.springframework.transaction.support.TransactionSynchronizationManager.getResource(entityManagerFactory);
        if (resource instanceof org.springframework.orm.jpa.EntityManagerHolder holder
                && !owner.equals(holder.getEntityManager().unwrap(org.hibernate.Session.class).getTenantIdentifierValue())) {
            throw new AccessDeniedException("禁止在持久化会话中切换账号");
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && (!(authentication.getPrincipal() instanceof AuthPrincipal principal)
                || !authentication.isAuthenticated() || !principal.id().equals(owner)
                || principal.role() != AuthRole.USER || !principal.isEnabled())) {
            throw new AccessDeniedException("无权执行此操作");
        }
        if (!users.existsByIdAndRoleAndEnabledTrue(owner, AuthRole.USER)) {
            throw new AccessDeniedException("无权执行此操作");
        }
        return owner;
    }
}
