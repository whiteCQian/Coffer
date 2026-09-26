package com.coffer.auth.service;

import com.coffer.auth.domain.AppUser;
import com.coffer.auth.domain.AuthRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.security.Principal;
import java.util.Collection;
import java.util.List;

/** Minimal authenticated identity stored in the server-side session. */
public final class AuthPrincipal implements UserDetails, Principal {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long id;
    private final String username;
    private final String passwordHash;
    private final AuthRole role;
    private final boolean enabled;

    public AuthPrincipal(Long id, String username, String passwordHash, AuthRole role, boolean enabled) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
    }

    private AuthPrincipal(Long id, String username, AuthRole role, boolean enabled) {
        this(id, username, null, role, enabled);
    }

    public static AuthPrincipal from(AppUser user) {
        return new AuthPrincipal(user.getId(), user.getUsername(), user.getPasswordHash(), user.getRole(), user.isEnabled());
    }

    public AuthPrincipal forSession() {
        return new AuthPrincipal(id, username, role, enabled);
    }

    public Long id() { return id; }
    public AuthRole role() { return role; }

    @Override public String getName() { return username; }
    @Override public String getUsername() { return username; }
    @Override public String getPassword() { return passwordHash; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return enabled; }
}
