package com.coffer.file.api;

import com.coffer.dto.Result;
import com.coffer.file.application.FileWriteIntentRecovery;
import com.coffer.file.application.FileWriteIntentService;
import com.coffer.file.application.StorageDeletionTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/** Owner-scoped recovery state; no storage key or file body is returned. */
@RestController
@RequestMapping("/api/file-operations")
@RequiredArgsConstructor
public class FileWriteIntentController {
    private final FileWriteIntentService intents;
    private final FileWriteIntentRecovery recovery;
    private final StorageDeletionTaskService deletions;
    private final com.coffer.file.application.FileRenameIntentService renames;

    @GetMapping("/writes")
    public Result<List<FileWriteIntentService.IntentView>> writes(@RequestParam(defaultValue = "0") int page) {
        return Result.success(intents.recent(page));
    }

    @PostMapping("/writes/{id}/restore")
    public Result<Long> restore(@PathVariable String id) { return Result.success(recovery.attachOrphan(id)); }

    @PostMapping("/writes/{id}/discard")
    public Result<Void> discardWrite(@PathVariable String id, @RequestBody DigestReview request) {
        recovery.requestOrphanDiscard(id, request.sha256());
        return Result.success();
    }

    @PostMapping("/writes/{id}/reconcile")
    public Result<Void> reconcileWrite(@PathVariable String id) {
        recovery.reconcile(id);
        return Result.success();
    }

    @GetMapping("/deletions")
    public Result<List<StorageDeletionTaskService.DeletionView>> deletions(@RequestParam(defaultValue = "0") int page) {
        return Result.success(deletions.recent(page));
    }

    @PostMapping("/deletions/{id}/retry")
    public Result<Void> retryDeletion(@PathVariable Long id) {
        deletions.retry(id);
        return Result.success();
    }

    public record DigestReview(String sha256) { }
    @PostMapping("/deletions/{id}/confirm")
    public Result<Void> confirmDeletion(@PathVariable Long id, @RequestBody DigestReview request) {
        deletions.confirmIdentity(id, request.sha256());
        return Result.success();
    }

    @GetMapping("/renames")
    public Result<List<com.coffer.file.application.FileRenameIntentService.View>> renames(@RequestParam(defaultValue = "0") int page) {
        return Result.success(renames.recent(page));
    }

    @PostMapping("/renames/{id}/retry")
    public Result<Void> retryRename(@PathVariable String id) {
        renames.retry(id);
        return Result.success();
    }
}
