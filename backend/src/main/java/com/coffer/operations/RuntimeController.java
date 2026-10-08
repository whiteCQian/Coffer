package com.coffer.operations;
import com.coffer.auth.service.AdminAuthorization;
import com.coffer.dto.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequiredArgsConstructor
public class RuntimeController {
    private final RuntimeMonitor monitor;
    private final AdminAuthorization authorization;
    @GetMapping("/api/runtime/status") public Result<RuntimeMonitor.Snapshot> status() { return Result.success(monitor.publicStatus()); }
    @GetMapping("/api/admin/runtime") public Result<RuntimeMonitor.Snapshot> admin() { authorization.requireAdmin(); return Result.success(monitor.snapshot()); }
    @GetMapping("/api/admin/runtime/alerts") public Result<List<RuntimeMonitor.AlertEvent>> alerts() { authorization.requireAdmin(); return Result.success(monitor.history()); }
}
