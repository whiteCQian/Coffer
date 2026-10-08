package com.coffer.inbox.api;

import com.coffer.dto.Result;
import com.coffer.inbox.api.dto.InboxImportProgressResponse;
import com.coffer.inbox.application.InboxImportScanner;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    @PostMapping("/{id}/retry")
    public Result<Void> retry(@PathVariable Long id) {
        inboxImportScanner.retry(id);
        return Result.success();
    }
}
