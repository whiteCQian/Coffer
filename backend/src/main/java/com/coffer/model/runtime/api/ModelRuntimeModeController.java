package com.coffer.model.runtime.api;

import com.coffer.dto.Result;
import com.coffer.model.runtime.ModelRuntimeModeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Global API/local runtime mode management. */
@RestController
@RequestMapping("/api/settings/runtime")
@RequiredArgsConstructor
public class ModelRuntimeModeController {

    private final ModelRuntimeModeService modeService;

    @GetMapping
    public Result<ModelRuntimeModeStatusResponse> status() {
        return Result.success(modeService.status());
    }

    @PostMapping("/validate")
    public Result<ModelRuntimeModeStatusResponse> validate(
            @Valid @RequestBody ModelRuntimeModeRequest request) {
        return Result.success(modeService.validate(request.getMode()));
    }

    @PutMapping("/mode")
    public Result<ModelRuntimeModeStatusResponse> switchMode(
            @Valid @RequestBody ModelRuntimeModeRequest request) {
        return Result.success(modeService.switchMode(request.getMode()));
    }
}
