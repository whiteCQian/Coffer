package com.coffer.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查控制器，用于验证应用是否成功启动。
 */
@RestController
public class HealthController {

    /**
     * 健康检查接口。
     *
     * @return 服务健康状态字符串
     */
    @GetMapping("/health")
    public String health() {
        return "Coffer service is healthy";
    }
}
