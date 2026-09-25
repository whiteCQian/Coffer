package com.coffer.inbox.api;

import com.coffer.dto.Result;
import com.coffer.inbox.api.dto.InboxImportProgressResponse;
import com.coffer.inbox.application.InboxImportScanner;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only progress endpoint for the configured batch inbox importer. */
@RestController
@RequestMapping("/api/inbox-imports")
@RequiredArgsConstructor
public class InboxImportController {

    private final InboxImportScanner inboxImportScanner;

    @GetMapping("/progress")
    public Result<InboxImportProgressResponse> getProgress() {
        return Result.success(inboxImportScanner.getProgress());
    }
}
