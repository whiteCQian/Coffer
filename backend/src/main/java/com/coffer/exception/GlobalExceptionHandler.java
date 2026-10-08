package com.coffer.exception;

import com.coffer.dto.Result;
import com.coffer.file.storage.StorageConflictException;
import com.coffer.file.storage.StorageObjectNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.net.SocketTimeoutException;

/**
 * 全局异常处理器：统一返回 {@code {code, msg, data}} 格式的 {@link Result}。
 *
 * <p>映射规则：
 * <ul>
 *   <li>参数校验异常（{@code @Valid}）→ 400</li>
 *   <li>业务/参数非法异常（{@code IllegalArgumentException}）→ 400</li>
 *   <li>文件上传超限（{@code MaxUploadSizeExceededException}）→ 400</li>
 *   <li>存储对象不存在/冲突 → 404/409</li>
 *   <li>兜底异常 → 500</li>
 * </ul>
 *
 * <p>存储适配器将部署相关异常映射到统一存储端口的异常合同。
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(com.coffer.operations.RuntimeStatusUnavailableException.class)
    public ResponseEntity<Result<?>> handleRuntimeStatusUnavailable(Exception exception) {
        return build(503, "运行状态暂时无法核实，请检查服务连接后重试");
    }
    @ExceptionHandler(com.coffer.model.runtime.ModelConsentRequiredException.class)
    public ResponseEntity<Result<?>> handleModelConsent(Exception exception) {
        return build(428, "模型目标未确认或配置已变化，请重新确认本次任务");
    }

    @ExceptionHandler(com.coffer.service.FileVersionConflictException.class)
    public ResponseEntity<Result<?>> handleStaleCitation(Exception exception) {
        return build(409, "文件已变更，请重新检索");
    }

    @ExceptionHandler(com.coffer.auth.service.ResourceNotFoundException.class)
    public ResponseEntity<Result<?>> handleResourceNotFound(Exception exception) {
        return build(404, "资源不存在");
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Result<?>> handleAccessDenied(Exception exception) {
        return build(403, "无权执行此操作");
    }

    private static final String MSG_UNAVAILABLE = "文件存储暂时不可用，请稍后重试";
    private static final String MSG_TIMEOUT = "连接文件存储超时，请稍后重试";
    private static final String MSG_UPLOAD_TOO_LARGE = "文件大小超过限制（最大 32MB）";
    private static final String MSG_INVALID_BODY = "请求体格式错误，请检查 JSON 语法与字符编码";

    @ExceptionHandler(StorageObjectNotFoundException.class)
    public ResponseEntity<Result<?>> handleMissingStoredObject(StorageObjectNotFoundException e) {
        return build(404, "文件正文不存在");
    }

    @ExceptionHandler(StorageConflictException.class)
    public ResponseEntity<Result<?>> handleStorageConflict(StorageConflictException e) {
        return build(409, "文件正文已变化或目标路径已占用");
    }

    /**
     * Socket 超时（IOException 子类，需在 IOException 之前匹配）。
     */
    @ExceptionHandler(SocketTimeoutException.class)
    public ResponseEntity<Result<?>> handleSocketTimeout(SocketTimeoutException e) {
        log.error("文件存储操作失败，异常类型={}", e.getClass().getSimpleName());
        return build(504, MSG_TIMEOUT);
    }

    /**
     * 其它 IO 异常（连接被拒绝、中断等）。
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Result<?>> handleIo(IOException e) {
        log.error("文件存储操作失败，异常类型={}", e.getClass().getSimpleName());
        return build(504, MSG_TIMEOUT);
    }

    /**
     * 文件上传超限：文件大小超过 {@code spring.servlet.multipart.max-file-size}（32MB），
     * 由 servlet 容器在进入 Controller 之前抛出。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<?>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("文件上传超过大小限制");
        return build(400, MSG_UPLOAD_TOO_LARGE);
    }

    /**
     * 参数非法（业务校验失败，如文件不存在、关联不存在），返回 400，避免被兜底误判为 500。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<?>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数校验失败");
        return build(400, "请求参数不符合要求，请检查输入后重试");
    }

    /**
     * 请求体解析失败：JSON 语法错误、非法字符编码（如非 UTF-8 的请求体）等，
     * 由 Jackson 在进入 Controller 前抛出 {@code HttpMessageNotReadableException}。
     *
     * <p>注意：此类异常的 cause 链含 {@code JsonParseException extends IOException}，
     * 若落入 {@link #handleRuntime} 的兜底拆解会被误判为文件存储网络错误（504），
     * 故需在此处优先匹配并返回 400。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<?>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败");
        return build(400, MSG_INVALID_BODY);
    }

    /**
     * {@code @RequestBody @Valid} 参数校验失败（字段为 null、空白等），
     * 提取首个字段错误信息返回 400，保持统一 {@link Result} 响应格式。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<?>> handleValidation(MethodArgumentNotValidException e) {
        log.warn("请求字段校验失败");
        return build(400, "请求字段校验失败，请检查必填项、格式和长度");
    }

    /**
     * 存储适配器可能包装 I/O 异常；此处只解出通用超时、冲突和缺失语义。
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Result<?>> handleRuntime(RuntimeException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof StorageObjectNotFoundException missing) return handleMissingStoredObject(missing);
            if (cause instanceof StorageConflictException conflict) return handleStorageConflict(conflict);
            if (cause instanceof SocketTimeoutException ste) {
                return handleSocketTimeout(ste);
            }
            if (cause instanceof IOException) return build(503, MSG_UNAVAILABLE);
            cause = cause.getCause();
        }
        log.error("系统内部异常，类型={}", e.getClass().getSimpleName());
        return build(500, "系统内部错误，请稍后重试");
    }

    /**
     * 构建统一 JSON 响应，HTTP 状态码与业务 code 保持一致。
     */
    private ResponseEntity<Result<?>> build(int status, String msg) {
        return ResponseEntity.status(status).body(Result.error(status, msg));
    }
}
