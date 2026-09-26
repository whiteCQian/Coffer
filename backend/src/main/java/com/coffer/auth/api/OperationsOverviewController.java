package com.coffer.auth.api;

import com.coffer.auth.service.OperationsOverviewService;
import com.coffer.dto.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/admin/operations") @RequiredArgsConstructor
public class OperationsOverviewController {
    private final OperationsOverviewService service;
    @GetMapping public Result<Map<String, Long>> overview() { return Result.success(service.overview()); }
}
