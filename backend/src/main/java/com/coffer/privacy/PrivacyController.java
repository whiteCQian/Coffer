package com.coffer.privacy;

import com.coffer.auth.service.*;
import com.coffer.dto.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

@RestController @RequestMapping("/api/privacy") @RequiredArgsConstructor
public class PrivacyController {
    private final PrivacyService privacy;
    private final OwnerAuthorization authorization;
    @GetMapping public Result<Map<String, Long>> status() { return Result.success(privacy.status()); }
    @GetMapping("/export") public void export(HttpServletResponse response) throws IOException {
        authorization.requireOwner();
        var archive = java.nio.file.Files.createTempFile("coffer-export-", ".zip");
        try {
            try (var output = java.nio.file.Files.newOutputStream(archive)) { privacy.export(output); }
            authorization.requireOwner();
            response.setContentType("application/zip");
            response.setHeader("Content-Disposition", "attachment; filename=coffer-private-export.zip");
            response.setHeader("Cache-Control", "no-store");
            response.setContentLengthLong(java.nio.file.Files.size(archive));
            java.nio.file.Files.copy(archive, response.getOutputStream());
        } finally { java.nio.file.Files.deleteIfExists(archive); }
    }
    @DeleteMapping("/conversations") public Result<Void> erase(@RequestBody EraseRequest request) {
        Long owner = authorization.requireOwner();
        try (var gate = PrivateWorkspaceGate.erase(owner)) { privacy.eraseConversations(request.confirmation()); }
        return Result.success();
    }
    public record EraseRequest(String confirmation) {}
}
