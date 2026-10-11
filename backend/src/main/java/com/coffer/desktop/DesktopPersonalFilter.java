package com.coffer.desktop;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Runs inside the security chain, after context loading and before workspace resolution. */
public final class DesktopPersonalFilter extends OncePerRequestFilter {
    private final DesktopPersonalWorkspace workspace;
    public DesktopPersonalFilter(DesktopPersonalWorkspace workspace) { this.workspace = workspace; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        var context = SecurityContextHolder.createEmptyContext();
        var principal = workspace.principal();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        try { chain.doFilter(request, response); } finally { SecurityContextHolder.clearContext(); }
    }
}
