package com.coffer.desktop;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Installed shell capabilities supplement, never replace, user sessions and CSRF. */
@Component @Profile("desktop") @Order(Ordered.HIGHEST_PRECEDENCE)
public final class DesktopLaunchFilter extends OncePerRequestFilter {
    private final byte[] token;
    public DesktopLaunchFilter(@Value("${COFFER_DESKTOP_LAUNCH_TOKEN:}") String value,
                               @Value("${coffer.desktop.shell:false}") boolean shell) {
        if (shell && !value.matches("[0-9a-f]{64}")) throw new IllegalStateException("桌面启动凭据缺失");
        token = value.getBytes(StandardCharsets.UTF_8);
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-Coffer-Desktop-Token");
        if (token.length > 0 && (supplied == null || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8)))) {
            response.setStatus(403); response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"code\":403,\"msg\":\"请从受信桌面窗口访问\",\"data\":null}");
            return;
        }
        chain.doFilter(request, response);
    }
}
