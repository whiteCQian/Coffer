package com.coffer.auth.api.dto;

import com.coffer.auth.domain.AppUser;

import java.time.LocalDateTime;

public record AuthUserResponse(Long id, String username, String role, boolean enabled, LocalDateTime createdAt) {
    public static AuthUserResponse from(AppUser user) {
        return new AuthUserResponse(user.getId(), user.getUsername(), user.getRole().name(),
                user.isEnabled(), user.getCreatedAt());
    }
}
