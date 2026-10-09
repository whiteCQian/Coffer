package com.coffer.desktop;

import com.coffer.dto.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @Profile("desktop") @RequiredArgsConstructor
public class WorkCopyController {
    private final WorkCopyService copies;
    @GetMapping("/api/desktop/work-copies") public Result<List<WorkCopyService.View>> list(@RequestParam(defaultValue="0") int page) { return Result.success(copies.recent(page)); }
    @PostMapping("/api/desktop/work-copies") public Result<WorkCopyService.View> create(@RequestBody Create request) { return Result.success(copies.create(request.fileId())); }
    @GetMapping("/api/desktop/work-copies/{id}") public Result<WorkCopyService.View> get(@PathVariable String id) { return Result.success(copies.get(id)); }
    @PostMapping("/api/desktop/work-copies/{id}/open") public Result<WorkCopyService.View> open(@PathVariable String id) { return Result.success(copies.open(id)); }
    @PostMapping("/api/desktop/work-copies/{id}/save") @com.coffer.model.runtime.ModelSubmission("WORK_SAVE")
    public Result<WorkCopyService.View> save(@PathVariable String id) { return Result.success(copies.save(id)); }
    @PostMapping("/api/desktop/work-copies/{id}/save-as") public Result<WorkCopyService.View> saveAs(@PathVariable String id) { return Result.success(copies.saveAs(id)); }
    @PostMapping("/api/desktop/work-copies/{id}/close") public Result<WorkCopyService.View> close(@PathVariable String id, @RequestBody Close request) { return Result.success(copies.close(id, request.action(), request.sha256())); }
    public record Create(Long fileId) { }
    public record Close(String action, String sha256) { }
}
