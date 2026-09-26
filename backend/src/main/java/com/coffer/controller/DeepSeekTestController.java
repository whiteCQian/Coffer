package com.coffer.controller;

import com.coffer.dto.Result;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

/** Compatibility route: user content must use the consent-gated chat endpoint. */
@RestController @RequestMapping("/api/test")
public class DeepSeekTestController {
    @GetMapping("/hello")
    public ResponseEntity<Result<Void>> hello() {
        return ResponseEntity.status(410).body(Result.error(410, "请使用模型设置中的连通性测试；对话请通过 AI 管家提交"));
    }
}
