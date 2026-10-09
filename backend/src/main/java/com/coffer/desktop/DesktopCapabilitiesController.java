package com.coffer.desktop;

import com.coffer.dto.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequiredArgsConstructor
public class DesktopCapabilitiesController {
    private final Environment environment;
    @GetMapping("/api/desktop/capabilities") public Result<Map<String, Boolean>> capabilities() {
        return Result.success(Map.of("workCopies", environment.matchesProfiles("desktop")));
    }
}
