package com.coffer.auth.api;

import com.coffer.auth.api.dto.AuthStatusResponse;
import com.coffer.auth.api.dto.AuthUserResponse;
import com.coffer.auth.api.dto.ChangePasswordRequest;
import com.coffer.auth.api.dto.InitialAdminRequest;
import com.coffer.auth.api.dto.LoginRequest;
import com.coffer.auth.domain.AppUser;
import com.coffer.auth.service.AccountService;
import com.coffer.auth.service.AuthPrincipal;
import com.coffer.auth.service.RequestRateLimiter;
import com.coffer.auth.service.ClientAddressResolver;
import com.coffer.dto.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AccountService accounts;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final RequestRateLimiter rateLimiter;
    private final ClientAddressResolver clientAddressResolver;

    @GetMapping("/status")
    public Result<AuthStatusResponse> status() {
        return Result.success(new AuthStatusResponse(accounts.isSetupRequired(), accounts.isSetupAvailable()));
    }

    @GetMapping("/csrf")
    public Result<String> csrf(CsrfToken token) {
        return Result.success(token.getToken());
    }

    @PostMapping("/setup")
    public Result<AuthUserResponse> setup(@Valid @RequestBody InitialAdminRequest body,
                                          HttpServletRequest request, HttpServletResponse response) {
        rateLimiter.requireAllowed("setup:" + clientAddressResolver.clientKey(request), 5, Duration.ofMinutes(15));
        AppUser user = accounts.createInitialAdmin(body.setupToken(), body.username(), body.password());
        authenticate(user.getUsername(), body.password(), request, response);
        return Result.success(AuthUserResponse.from(user));
    }

    @PostMapping("/login")
    public Result<AuthUserResponse> login(@Valid @RequestBody LoginRequest body,
                                          HttpServletRequest request, HttpServletResponse response) {
        String username = body.username().trim().toLowerCase(java.util.Locale.ROOT);
        rateLimiter.requireAllowed("login:" + clientAddressResolver.clientKey(request), 10, Duration.ofMinutes(15));
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, body.password()));
            saveAuthentication(authentication, request, response);
            AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
            return Result.success(new AuthUserResponse(principal.id(), principal.getUsername(),
                    principal.role().name(), true, accounts.getCreatedAt(principal.id())));
        } catch (AuthenticationException exception) {
            throw new com.coffer.auth.service.AuthFailureException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }
    }

    @GetMapping("/me")
    public Result<AuthUserResponse> me(Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return Result.success(new AuthUserResponse(principal.id(), principal.getUsername(),
                principal.role().name(), true, accounts.getCreatedAt(principal.id())));
    }

    @PostMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest body,
                                       Authentication authentication,
                                       HttpServletRequest request) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        rateLimiter.requireAllowed("password:" + clientAddressResolver.clientKey(request) + ":" + principal.id(), 5, Duration.ofHours(1));
        HttpSession session = request.getSession(false);
        accounts.changeOwnPassword(principal, body.currentPassword(), body.newPassword(),
                session == null ? null : session.getId());
        return Result.success();
    }

    private void authenticate(String username, String password, HttpServletRequest request,
                              HttpServletResponse response) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        saveAuthentication(authentication, request, response);
    }

    private void saveAuthentication(Authentication authentication, HttpServletRequest request,
                                    HttpServletResponse response) {
        request.getSession(true);
        request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        AuthPrincipal authenticated = (AuthPrincipal) authentication.getPrincipal();
        Authentication sessionAuthentication = UsernamePasswordAuthenticationToken.authenticated(
                authenticated.forSession(), null, authentication.getAuthorities());
        context.setAuthentication(sessionAuthentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

}
