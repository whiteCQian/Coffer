package com.coffer.web;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import com.coffer.dto.Result;
@RestController @Profile("prod") @RequiredArgsConstructor
public class WebLimitsController {
    private final WebLimits limits;
    @GetMapping("/api/storage/usage") public Result<WebLimits.Usage> usage() { return Result.success(limits.usage()); }
}
