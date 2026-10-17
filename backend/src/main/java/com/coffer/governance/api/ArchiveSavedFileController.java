package com.coffer.governance.api;

import com.coffer.dto.Result;
import com.coffer.governance.api.dto.*;
import com.coffer.governance.application.ArchiveSavedFileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/files")
public class ArchiveSavedFileController {
    private final ArchiveSavedFileService service;
    @PostMapping("/{fileId}/confirm-archive")
    public Result<ArchiveOperationBatchResponse> confirm(@PathVariable Long fileId,
            @Valid @RequestBody ArchiveSavedFileRequest request) {
        return Result.success(service.confirm(fileId, request));
    }
}
