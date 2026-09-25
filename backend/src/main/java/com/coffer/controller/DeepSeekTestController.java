package com.coffer.controller;

import com.coffer.annotation.LogModelCall;
import com.coffer.dto.Result;
import com.coffer.model.provider.ChatProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DeepSeek API 连通性测试控制器。
 *
 * <p>调用默认 {@link ChatProvider}（当前配置为 DeepSeek 兼容接口），发送一句问候，
 * 返回模型回复，用于验证 api-key / 网络 / 模型是否可用。
 */
@Slf4j
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class DeepSeekTestController {

    private final ChatProvider chatProvider;

    /**
     * Hello 测试：向 DeepSeek 发送一句话并返回回复。
     *
     * @param message 要发送的消息，默认 "Hello"
     * @return 模型回复（统一 Result 封装）
     */
    @GetMapping("/hello")
    @LogModelCall
    public Result<String> hello(@RequestParam(defaultValue = "Hello") String message) {
        long start = System.currentTimeMillis();
        try {
            String reply = chatProvider.chat(message);
            long cost = System.currentTimeMillis() - start;
            log.info("DeepSeek 调用成功，耗时 {}ms，回复: {}", cost, reply);
            return Result.success(reply);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("DeepSeek 调用失败，耗时 {}ms: {}", cost, e.getMessage(), e);
            return Result.error(500, "DeepSeek 调用失败: " + e.getMessage());
        }
    }
}
