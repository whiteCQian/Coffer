package com.coffer.operations;
import com.coffer.dto.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/admin/runtime/key-rotation") @RequiredArgsConstructor
public class MasterKeyRotationController {
    private final MasterKeyRotationService rotation;
    @GetMapping public Result<MasterKeyRotationService.Status> status() { return Result.success(rotation.status()); }
    @PostMapping public Result<MasterKeyRotationService.Status> rotate() { return Result.success(rotation.rotate()); }
}
