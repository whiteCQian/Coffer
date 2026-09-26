package com.coffer.controller;

import com.coffer.dto.Result;
import com.coffer.service.ModelDiagnosticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;

@RestController @RequestMapping("/api/model-diagnostics") @RequiredArgsConstructor
public class ModelDiagnosticsController {
    private final ModelDiagnosticsService diagnostics;
    @GetMapping
    public Result<Page<ModelDiagnosticsService.Diagnostic>> list(@RequestParam(defaultValue = "0") int page) { return Result.success(diagnostics.list(page)); }
    @DeleteMapping
    public Result<Void> delete() { diagnostics.delete(); return Result.success(); }
}
