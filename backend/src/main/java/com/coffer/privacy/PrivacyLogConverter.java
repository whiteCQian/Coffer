package com.coffer.privacy;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.Map;

/** Only registered static event codes enter host logs. Arguments and throwable text never do. */
public final class PrivacyLogConverter extends ClassicConverter {
    private static final Map<String, String> EVENTS = Map.ofEntries(
            Map.entry("系统内部异常，类型={}", "REQUEST_FAILED"),
            Map.entry("参数校验失败", "INVALID_REQUEST"),
            Map.entry("请求体解析失败", "INVALID_BODY"),
            Map.entry("请求字段校验失败", "INVALID_FIELD"),
            Map.entry("文件存储操作失败，异常类型={}", "STORAGE_FAILED"),
            Map.entry("文件处理管道执行失败，异常类型={}", "PROCESSING_FAILED"),
            Map.entry("文件处理管道完成", "PROCESSING_COMPLETED"),
            Map.entry("模型调用异常，类型={}", "MODEL_FAILED"),
            Map.entry("模型调用成功，耗时 {}ms", "MODEL_COMPLETED"),
            Map.entry("MinIO 存储桶创建成功", "BUCKET_CREATED"),
            Map.entry("MinIO 存储桶初始化失败（不阻止应用启动），异常类型={}", "BUCKET_FAILED"));
    @Override public String convert(ILoggingEvent event) {
        return EVENTS.getOrDefault(event.getMessage(), "EVENT_RECORDED");
    }
}
