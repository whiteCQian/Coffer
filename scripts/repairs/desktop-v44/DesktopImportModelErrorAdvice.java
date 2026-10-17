package com.coffer.desktop;

import com.coffer.dto.Result;
import com.coffer.inbox.api.InboxImportController;
import com.coffer.model.runtime.api.ModelExecutionSnapshotController;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Narrow backport for V44: newer distributions already have typed model-mode errors. */
@Profile("desktop")
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = { InboxImportController.class, ModelExecutionSnapshotController.class })
public class DesktopImportModelErrorAdvice {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<?>> invalidImport(IllegalArgumentException failure) {
        String message = "请求参数不符合要求，请检查输入后重试";
        for (String mode : new String[] { "API", "LOCAL" }) {
            // Only these fixed, known V44 errors may be exposed; arbitrary exception details stay private.
            if (("当前运行模式尚未通过模型连通性校验，请先验证 " + mode + " 模式").equals(failure.getMessage())) {
                String label = "LOCAL".equals(mode) ? "本地模式（LOCAL）" : "API 模式";
                message = "当前启用的" + label + "尚未通过完整校验。请在设置→模型连接中保存 Chat 和 Vision 配置，"
                        + "点击“测试当前模式”；如需使用其他模式，校验通过后点击“启用该模式”，再返回收件箱确认入库。源文件已保留。";
                break;
            }
        }
        return ResponseEntity.badRequest().body(Result.error(400, message));
    }
}
