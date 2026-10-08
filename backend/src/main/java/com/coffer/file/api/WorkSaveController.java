package com.coffer.file.api;

import com.coffer.dto.Result;
import com.coffer.file.application.WorkSaveApplicationService;
import com.coffer.file.application.WorkSaveIntentService;
import com.coffer.file.application.WorkSaveRecovery;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Version-checked working-copy save and owner-visible recovery ledger. */
@RestController @RequiredArgsConstructor
public class WorkSaveController {
    private final WorkSaveApplicationService saves;
    private final WorkSaveIntentService intents;
    private final WorkSaveRecovery recovery;

    @PostMapping(value = "/api/files/{id}/work-save", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @com.coffer.model.runtime.ModelSubmission("WORK_SAVE")
    public Result<WorkSaveApplicationService.Result> save(@PathVariable Long id,
            @RequestParam long expectedRevision, @RequestParam String expectedSha256,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam("file") MultipartFile file) {
        return Result.success(saves.save(id, expectedRevision, expectedSha256, idempotencyKey, file));
    }

    @GetMapping("/api/file-operations/work-saves")
    public Result<List<WorkSaveIntentService.View>> list(@RequestParam(defaultValue = "0") int page) {
        return Result.success(intents.recent(page));
    }

    @PostMapping("/api/file-operations/work-saves/{id}/reconcile")
    public Result<Void> reconcile(@PathVariable String id) {
        recovery.reconcile(id);
        return Result.success();
    }

    @PostMapping("/api/file-operations/work-saves/{id}/restore")
    public Result<Long> restore(@PathVariable String id) {
        return Result.success(intents.restoreAsNewFile(id));
    }
}
