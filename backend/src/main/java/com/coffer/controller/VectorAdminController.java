package com.coffer.controller;

import com.coffer.entity.VectorReindexJob;
import com.coffer.dto.Result;
import com.coffer.service.VectorReindexService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/vector")
@RequiredArgsConstructor
public class VectorAdminController {
    private final VectorReindexService service;

    @PostMapping("/reindex")
    public Result<VectorReindexJob> start() { return Result.success(service.start()); }

    @GetMapping("/reindex/{jobId}")
    public Result<VectorReindexJob> status(@PathVariable String jobId) {
        VectorReindexJob job = service.get(jobId);
        return job == null ? Result.error(404, "重建任务不存在: " + jobId) : Result.success(job);
    }
}
