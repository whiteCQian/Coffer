package com.coffer.desktop;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import com.coffer.file.infrastructure.storage.SafeLocalPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;

/** Ready rendezvous is instance/PID bound. Parent pipe EOF closes H2 and releases both library locks. */
@Component @Profile("desktop")
public class DesktopShellLifecycle {
    private final boolean shell;
    private final String instance;
    private final DesktopDataDirectory data;
    private final ConfigurableApplicationContext context;
    private final ObjectMapper json;
    public DesktopShellLifecycle(@Value("${coffer.desktop.shell:false}") boolean shell,
                                 @Value("${COFFER_DESKTOP_INSTANCE_ID:}") String instance,
                                 @Value("${COFFER_DESKTOP_PARENT_PID:0}") long parentPid,
                                 DesktopDataDirectory data, ConfigurableApplicationContext context, ObjectMapper json) {
        this.shell=shell; this.instance=instance; this.data=data; this.context=context; this.json=json;
        if (shell && (parentPid <= 0 || ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(0L) != parentPid))
            throw new IllegalStateException("桌面后端进程归属验证失败");
    }
    @EventListener(ApplicationReadyEvent.class)
    public void ready() throws Exception {
        if (!shell) return;
        java.util.UUID.fromString(instance);
        Path runtime=data.root().resolve(".coffer-runtime");
        if (!Files.exists(runtime, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(runtime);
        SafeLocalPaths.directory(runtime);
        int port=((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext) context).getWebServer().getPort();
        byte[] bytes=json.writeValueAsBytes(java.util.Map.of("instanceId",instance,"pid",ProcessHandle.current().pid(),"port",port));
        Path stage=Files.createTempFile(runtime,".ready-",".tmp");
        try {
            try(var channel=java.nio.channels.FileChannel.open(stage,StandardOpenOption.WRITE)) {
                var buffer=java.nio.ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            Files.move(stage,runtime.resolve("ready-"+instance+".json"),StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(stage); }
        var monitor=new Thread(() -> {
            try(var input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8))) {
                while(true) { String line=input.readLine(); if(line==null || "COFFER_SHUTDOWN".equals(line)) break; }
            } catch(java.io.IOException ignored) { }
            context.close(); System.exit(0);
        },"coffer-parent-lifetime");
        monitor.setDaemon(true); monitor.start();
    }
}
