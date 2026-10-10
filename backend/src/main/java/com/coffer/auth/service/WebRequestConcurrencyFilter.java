package com.coffer.auth.service;
import com.coffer.auth.domain.AuthRole;
import com.coffer.dto.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Admission before multipart temp-file creation. The single server permits 3 mutations,
 * at most 2 per account, and at most 1 multipart upload per account; no unbounded wait queue. */
public final class WebRequestConcurrencyFilter extends OncePerRequestFilter {
    private final boolean production;
    private final ObjectMapper json;
    private final Semaphore global = new Semaphore(3);
    private final ConcurrentHashMap<String, Integer> active = new ConcurrentHashMap<>();
    public WebRequestConcurrencyFilter(boolean production, ObjectMapper json) { this.production=production; this.json=json; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean exporting=request.getRequestURI().equals("/api/privacy/export");
        int cost=exporting?3:1;
        boolean multipart = request.getContentType()!=null && request.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("multipart/");
        if(production && request.getRequestURI().startsWith("/api/") && multipart
                && (authentication==null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof AuthPrincipal principal) || principal.role()!=AuthRole.USER)) {
            response.setStatus(authentication==null?401:403); response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getOutputStream(),Result.error(response.getStatus(),"请使用有效的普通用户账号上传文件")); return;
        }
        if (!production || !request.getRequestURI().startsWith("/api/") || !exporting && java.util.Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())
                || authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal user) || user.role()!=AuthRole.USER) {
            chain.doFilter(request,response); return;
        }
        String key = user.id()+":all", uploadKey=user.id()+":upload";
        boolean upload = request.getRequestURI().equals("/api/files/upload");
        boolean acquired=false;
        synchronized (active) {
            if (active.getOrDefault(key,0)<2 && (!upload || active.getOrDefault(uploadKey,0)==0) && global.tryAcquire(cost)) {
                active.merge(key,1,Integer::sum); if(upload) active.put(uploadKey,1); acquired=true;
            }
        }
        if (!acquired) {
            response.setStatus(429); response.setHeader("Retry-After","30"); response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getOutputStream(), Result.error(429,"账号或服务当前并发操作已达到上限，请等待正在进行的操作完成后重试")); return;
        }
        var released=new AtomicBoolean();
        Runnable release=() -> { if(released.compareAndSet(false,true)) synchronized(active) {
            active.computeIfPresent(key,(k,n)->n==1?null:n-1); if(upload) active.remove(uploadKey); global.release(cost);
        }};
        try { chain.doFilter(request,response); }
        finally {
            if(request.isAsyncStarted()) {
                try { request.getAsyncContext().addListener(new AsyncListener() {
                    public void onComplete(AsyncEvent e){release.run();} public void onTimeout(AsyncEvent e){release.run();}
                    public void onError(AsyncEvent e){release.run();} public void onStartAsync(AsyncEvent e){e.getAsyncContext().addListener(this);}
                }); } catch(IllegalStateException alreadyCompleted) { release.run(); }
            } else release.run();
        }
    }
}
