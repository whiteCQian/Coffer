package com.coffer.model.runtime.api;

import com.coffer.dto.Result;
import com.coffer.model.runtime.ModelExecutionSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/model-execution") @RequiredArgsConstructor
public class ModelExecutionSnapshotController {
    private final ModelExecutionSnapshotService snapshots;
    private final com.coffer.service.UserSecretRotationService rotation;
    @PostMapping("/rewrap-secrets")
    public Result<Void> rewrap() { rotation.rotate(); return Result.success(); }
    @GetMapping("/target")
    public Result<ModelExecutionSnapshotService.TargetPreview> preview() { return Result.success(snapshots.preview()); }
    @GetMapping("/snapshots/{id}")
    public Result<ModelExecutionSnapshotService.TargetPreview> get(@PathVariable String id) { return Result.success(snapshots.describe(id)); }
    @PostMapping("/inbox") @com.coffer.model.runtime.ModelSubmission("INBOX")
    public Result<Void> authorizeInbox() { snapshots.authorizeInbox(); return Result.success(); }
    @DeleteMapping("/inbox")
    public Result<Void> revokeInbox() { snapshots.revokeInbox(); return Result.success(); }
    @GetMapping("/inbox")
    public Result<ModelExecutionSnapshotService.TargetPreview> inbox() {
        String id = snapshots.inboxSnapshotId();
        return Result.success(id == null ? null : snapshots.describe(id));
    }
}
