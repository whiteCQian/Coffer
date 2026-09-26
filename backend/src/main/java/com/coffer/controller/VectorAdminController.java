package com.coffer.controller;

import com.coffer.entity.VectorReindexJob;
import com.coffer.dto.Result;
import com.coffer.service.VectorReindexService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/vector")
@RequiredArgsConstructor
public class VectorAdminController {
    private final VectorReindexService service;

    @PostMapping("/reindex")
    @com.coffer.model.runtime.ModelSubmission("VECTOR_REBUILD")
    public Result<VectorReindexJob> start() { return Result.success(service.start()); }

    @GetMapping("/reindex/{jobId}")
    public Result<VectorReindexJob> status(@PathVariable String jobId) {
        VectorReindexJob job = service.get(jobId);
        if (job == null) throw new com.coffer.auth.service.ResourceNotFoundException();
        return Result.success(job);
    }
}
