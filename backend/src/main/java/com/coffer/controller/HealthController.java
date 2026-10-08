package com.coffer.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查控制器，用于验证应用是否成功启动。
 */
@RestController
public class HealthController {
    private final com.coffer.operations.RuntimeMonitor monitor;
    public HealthController(com.coffer.operations.RuntimeMonitor monitor) { this.monitor = monitor; }

    /**
     * 健康检查接口。
     *
     * @return 服务健康状态字符串
     */
    @GetMapping("/health")
    public org.springframework.http.ResponseEntity<java.util.Map<String, String>> health() {
        String status = monitor.snapshot().readiness();
        return org.springframework.http.ResponseEntity.status(status.equals("UP") ? 200 : 503)
                .body(java.util.Map.of("status", status));
    }
}
