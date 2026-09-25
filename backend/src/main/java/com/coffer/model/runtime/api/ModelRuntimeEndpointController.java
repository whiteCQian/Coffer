package com.coffer.model.runtime.api;

import com.coffer.dto.Result;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.model.runtime.ModelRuntimeCapability;
import com.coffer.model.runtime.ModelRuntimeConnectivityTester;
import com.coffer.model.runtime.ModelRuntimeEndpointConfigurationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** C16 endpoint configuration and per-capability connectivity APIs. */
@RestController
@RequestMapping("/api/settings/runtime/config")
@RequiredArgsConstructor
public class ModelRuntimeEndpointController {

    private final ModelRuntimeEndpointConfigurationService configurationService;
    private final ModelRuntimeConnectivityTester connectivityTester;

    @GetMapping
    public Result<List<ModelRuntimeEndpointResponse>> list() {
        return Result.success(configurationService.list());
    }

    @PutMapping("/{mode}/{capability}")
    public Result<ModelRuntimeEndpointResponse> save(
            @PathVariable String mode,
            @PathVariable String capability,
            @Valid @RequestBody ModelRuntimeEndpointRequest request) {
        request.setMode(parseMode(mode));
        request.setCapability(parseCapability(capability));
        return Result.success(configurationService.save(request));
    }

    @DeleteMapping("/{mode}/{capability}")
    public Result<Void> delete(@PathVariable String mode, @PathVariable String capability) {
        configurationService.delete(parseMode(mode), parseCapability(capability));
        return Result.success();
    }

    @PostMapping("/test")
    public Result<ModelRuntimeEndpointTestResponse> test(
            @Valid @RequestBody ModelRuntimeEndpointRequest request) {
        return Result.success(connectivityTester.test(configurationService.resolveForTest(request)));
    }

    private GovernanceRunMode parseMode(String value) {
        try {
            return GovernanceRunMode.valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("不支持的运行模式: " + value);
        }
    }

    private ModelRuntimeCapability parseCapability(String value) {
        try {
            return ModelRuntimeCapability.valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("不支持的模型能力: " + value);
        }
    }
}
