package com.coffer.auth.api;

import com.coffer.auth.api.dto.AccountAuditResponse;
import com.coffer.auth.api.dto.AuthUserResponse;
import com.coffer.auth.api.dto.CreateUserRequest;
import com.coffer.auth.api.dto.ResetPasswordRequest;
import com.coffer.auth.domain.AccountAuditLog;
import com.coffer.auth.domain.AppUser;
import com.coffer.auth.service.AccountService;
import com.coffer.auth.service.AuthPrincipal;
import com.coffer.auth.service.RequestRateLimiter;
import com.coffer.auth.service.ClientAddressResolver;
import com.coffer.dto.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminUserController {

    private final AccountService accounts;
    private final RequestRateLimiter rateLimiter;
    private final ClientAddressResolver clientAddressResolver;
    @org.springframework.beans.factory.annotation.Autowired
    private com.coffer.auth.service.AccountDeletionService deletions;

    @GetMapping("/users")
    public Result<List<AuthUserResponse>> users() {
        return Result.success(accounts.listUsers().stream().map(AuthUserResponse::from).toList());
    }

    @PostMapping("/users")
    public Result<AuthUserResponse> create(@Valid @RequestBody CreateUserRequest body, Authentication authentication) {
        AppUser created = accounts.createUser((AuthPrincipal) authentication.getPrincipal(),
                body.username(), body.password());
        return Result.success(AuthUserResponse.from(created));
    }

    @PatchMapping("/users/{id}/disable")
    public Result<Void> disable(@PathVariable Long id, Authentication authentication) {
        accounts.disableUser((AuthPrincipal) authentication.getPrincipal(), id);
        return Result.success();
    }

    @PatchMapping("/users/{id}/enable")
    public Result<Void> enable(@PathVariable Long id, Authentication authentication) {
        accounts.enableUser((AuthPrincipal) authentication.getPrincipal(), id);
        return Result.success();
    }

    @PostMapping("/users/{id}/reset-password")
    public Result<Void> resetPassword(@PathVariable Long id,
                                      @Valid @RequestBody ResetPasswordRequest body,
                                      Authentication authentication,
                                      HttpServletRequest request) {
        AuthPrincipal actor = (AuthPrincipal) authentication.getPrincipal();
        String remote = clientAddressResolver.clientKey(request);
        rateLimiter.requireAllowed("reset:" + remote + ":" + actor.id(), 10, Duration.ofHours(1));
        accounts.resetPassword(actor, id, body.newPassword());
        return Result.success();
    }

    @GetMapping("/audit")
    public Result<List<AccountAuditResponse>> audit() {
        List<AccountAuditResponse> rows = accounts.listRecentAudit().stream()
                .map(AccountAuditResponse::from).toList();
        return Result.success(rows);
    }

    @DeleteMapping("/users/{id}")
    public Result<com.coffer.auth.service.AccountDeletionService.View> delete(@PathVariable long id,
            @RequestBody com.coffer.auth.service.AccountDeletionService.Request request) {
        return Result.success(deletions.request(id, request));
    }
    @PostMapping("/users/{id}/retry-deletion")
    public Result<com.coffer.auth.service.AccountDeletionService.View> retryDeletion(@PathVariable long id) {
        return Result.success(deletions.retry(id));
    }
}
