package com.coffer.auth.service;

import com.coffer.auth.domain.AuthRole;
import com.coffer.auth.infrastructure.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class AdminAuthorization {
    private final AppUserRepository users;
    public void requireAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AuthPrincipal actor)
                || actor.role() != AuthRole.ADMIN || !users.existsByIdAndRoleAndEnabledTrue(actor.id(), AuthRole.ADMIN)) {
            throw new AccessDeniedException("无权执行此操作");
        }
    }
}
