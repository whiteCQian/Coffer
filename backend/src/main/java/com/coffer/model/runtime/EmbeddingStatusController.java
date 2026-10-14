package com.coffer.model.runtime;

import com.coffer.dto.Result;
import com.coffer.governance.domain.GovernanceRunMode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/settings/embedding") @RequiredArgsConstructor
public class EmbeddingStatusController {
    private final AdminEmbeddingService service;
    @GetMapping public Result<AdminEmbeddingService.UserView> status(@RequestParam(defaultValue = "API") GovernanceRunMode mode) {
        return Result.success(service.status(mode));
    }
}
