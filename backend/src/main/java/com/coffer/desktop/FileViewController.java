package com.coffer.desktop;
import com.coffer.dto.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController @Profile("desktop")
public class FileViewController {
    private final FileViewService views;
    private final byte[] capability;
    public FileViewController(FileViewService views,@Value("${COFFER_DESKTOP_NATIVE_TOKEN:}")String token) {
        this.views=views;this.capability=token.matches("[a-f0-9]{64}")?token.getBytes(StandardCharsets.UTF_8):new byte[0];
    }
    @PostMapping("/api/desktop/files/{id}/open")
    public Result<FileViewService.View> open(@PathVariable Long id,@RequestBody(required=false) Request request,
            @RequestHeader(value="X-Coffer-Native-Capability",required=false)String header) {
        if(capability.length==0||header==null||!MessageDigest.isEqual(capability,header.getBytes(StandardCharsets.UTF_8)))
            throw new AccessDeniedException("请在桌面端打开文件");
        return Result.success(views.open(id,request==null?null:request.revision()));
    }
    public record Request(Long revision) { }
}
