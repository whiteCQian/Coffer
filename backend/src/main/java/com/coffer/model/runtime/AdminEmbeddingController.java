package com.coffer.model.runtime;

import com.coffer.dto.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admin/embedding") @RequiredArgsConstructor
public class AdminEmbeddingController {
    private final AdminEmbeddingService service;
    private final com.coffer.auth.service.RequestRateLimiter limiter;
    private final com.coffer.auth.service.ClientAddressResolver addresses;
    public record EnableRequest(boolean enabled) { }
    @GetMapping public Result<AdminEmbeddingService.AdminView> get() { return Result.success(service.getAdmin()); }
    @PutMapping public Result<AdminEmbeddingService.AdminView> save(@RequestBody AdminEmbeddingRequest request) { return Result.success(service.save(request)); }
    @PostMapping("/test") public Result<AdminEmbeddingService.TestResult> test(jakarta.servlet.http.HttpServletRequest request) {
        limiter.requireAllowed("admin-embedding-test:" + addresses.clientKey(request), 10, java.time.Duration.ofMinutes(15));
        return Result.success(service.validate());
    }
    @PutMapping("/enabled") public Result<AdminEmbeddingService.AdminView> enabled(@RequestBody EnableRequest request) { return Result.success(service.enable(request.enabled())); }
    @DeleteMapping public Result<AdminEmbeddingService.AdminView> clear() { return Result.success(service.clear()); }
}
