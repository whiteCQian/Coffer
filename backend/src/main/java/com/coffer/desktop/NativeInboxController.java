package com.coffer.desktop;

import com.coffer.dto.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController @Profile("desktop") @RequestMapping("/api/desktop/native-inbox")
public class NativeInboxController {
    private final NativeInboxService inbox;
    private final byte[] capability;
    public NativeInboxController(NativeInboxService inbox, @Value("${COFFER_DESKTOP_NATIVE_TOKEN:}") String value) {
        this.inbox = inbox; this.capability = value.matches("[0-9a-f]{64}") ? value.getBytes(StandardCharsets.UTF_8) : new byte[0];
    }
    private void authorize(String header) {
        if (capability.length == 0 || header == null || !MessageDigest.isEqual(capability, header.getBytes(StandardCharsets.UTF_8)))
            throw new AccessDeniedException("请通过桌面原生文件选择或拖放导入");
    }
    @PostMapping("/preview") public Result<NativeInboxService.Preview> preview(@RequestHeader(value="X-Coffer-Native-Capability", required=false) String header, @RequestBody Selection request) {
        authorize(header); return Result.success(inbox.preview(request.sourcePath()));
    }
    @PostMapping("/commit") public Result<Void> commit(@RequestHeader(value="X-Coffer-Native-Capability", required=false) String header, @RequestBody Confirmation request) {
        authorize(header); inbox.commit(request.sourcePath(), request.preview()); return Result.success();
    }
    public record Selection(String sourcePath) { }
    public record Confirmation(String sourcePath, NativeInboxService.Preview preview) { }
}
