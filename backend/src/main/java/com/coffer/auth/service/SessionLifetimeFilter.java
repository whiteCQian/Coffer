package com.coffer.auth.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.coffer.dto.Result;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import java.io.IOException;

/** JDBC idle timeout remains 30m; production additionally enforces an absolute session age. */
public final class SessionLifetimeFilter extends OncePerRequestFilter {
    private final boolean production;
    private final ObjectMapper json;
    public SessionLifetimeFilter(boolean production, ObjectMapper json) { this.production = production; this.json = json; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        var session = request.getSession(false);
        if (production && session != null && System.currentTimeMillis() - session.getCreationTime() >= 8L * 60 * 60 * 1000) {
            session.invalidate(); SecurityContextHolder.clearContext();
            response.setStatus(401); response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            json.writeValue(response.getOutputStream(), Result.error(401, "会话已达到有效期，请重新登录"));
            return;
        }
        chain.doFilter(request, response);
    }
}
