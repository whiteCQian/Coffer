package com.coffer.model.runtime.api;

import com.coffer.dto.Result;
import com.coffer.model.runtime.ModelContentGate;
import com.coffer.model.runtime.ModelRuntimeCapability;
import com.coffer.model.runtime.ModelSubmission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Review the local risk decision and exact target before approving one file version. */
@RestController @RequiredArgsConstructor @RequestMapping("/api/files/{fileId}/model-approval")
public class FileModelApprovalController {
    private final ModelContentGate gate;
    public record ApprovalRequest(boolean approved, Long revision, String contentSha256, String risk) { }

    @GetMapping
    public Result<ModelContentGate.Assessment> assess(@PathVariable Long fileId,
            @RequestParam ModelRuntimeCapability capability) {
        return Result.success(gate.assess(fileId, capability));
    }

    @PostMapping
    @ModelSubmission("FILE_CONTENT_APPROVAL")
    public Result<ModelContentGate.Assessment> approve(@PathVariable Long fileId,
            @RequestParam ModelRuntimeCapability capability, @RequestBody ApprovalRequest request) {
        if (request == null || !request.approved() || request.revision() == null
                || request.contentSha256() == null || request.risk() == null)
            throw new IllegalArgumentException("需要明确确认此文件版本的模型发送");
        return Result.success(gate.approve(fileId, capability, request.revision(),
                request.contentSha256(), request.risk()));
    }
}
