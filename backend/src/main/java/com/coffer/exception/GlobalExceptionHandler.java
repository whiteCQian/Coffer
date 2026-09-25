package com.coffer.exception;

import com.coffer.dto.Result;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.InsufficientDataException;
import io.minio.errors.InternalException;
import io.minio.errors.MinioException;
import io.minio.errors.ServerException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.security.InvalidKeyException;

/**
 * 全局异常处理器：统一返回 {@code {code, msg, data}} 格式的 {@link Result}。
 *
 * <p>映射规则：
 * <ul>
 *   <li>参数校验异常（{@code @Valid}）→ 400</li>
 *   <li>业务/参数非法异常（{@code IllegalArgumentException}）→ 400</li>
 *   <li>文件上传超限（{@code MaxUploadSizeExceededException}）→ 400</li>
 *   <li>MinIO 异常 → 503（服务不可用；桶/对象不存在细分 404）</li>
 *   <li>兜底异常 → 500</li>
 * </ul>
 *
 * <p>同时处理 {@link com.coffer.service.MinioStorageService} 将 MinIO 异常包装为
 * RuntimeException 抛出的情况（解开 cause 链还原为具体异常并复用上述映射）。
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final String MSG_NO_SUCH_BUCKET = "存储桶不存在，请联系管理员";
    private static final String MSG_NO_SUCH_KEY = "文件不存在";
    private static final String MSG_UNAVAILABLE = "MinIO 服务暂时不可用，请稍后重试";
    private static final String MSG_AUTH_FAILED = "MinIO 认证失败，请检查 AccessKey 和 SecretKey";
    private static final String MSG_TIMEOUT = "连接 MinIO 超时，请检查网络";
    private static final String MSG_UPLOAD_TOO_LARGE = "文件大小超过限制（最大 50MB）";
    private static final String MSG_INVALID_BODY = "请求体格式错误，请检查 JSON 语法与字符编码";

    /**
     * 处理存储桶/对象不存在的错误码，其它 ErrorResponseException 视为 MinIO 服务异常返回 503。
     */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<Result<?>> handleErrorResponse(ErrorResponseException e) {
        log.error("MinIO 操作异常: {}", e.getMessage(), e);
        String code = e.errorResponse() == null ? null : e.errorResponse().code();
        if ("NoSuchBucket".equals(code)) {
            return build(404, MSG_NO_SUCH_BUCKET);
        }
        if ("NoSuchKey".equals(code)) {
            return build(404, MSG_NO_SUCH_KEY);
        }
        return build(503, MSG_UNAVAILABLE);
    }

    /**
     * 网络或服务端异常：数据不足、内部错误、服务不可用。
     */
    @ExceptionHandler({InsufficientDataException.class, InternalException.class, ServerException.class})
    public ResponseEntity<Result<?>> handleUnavailable(Exception e) {
        log.error("MinIO 操作异常: {}", e.getMessage(), e);
        return build(503, MSG_UNAVAILABLE);
    }

    /**
     * 认证失败：AccessKey/SecretKey 错误。
     */
    @ExceptionHandler(InvalidKeyException.class)
    public ResponseEntity<Result<?>> handleInvalidKey(InvalidKeyException e) {
        log.error("MinIO 操作异常: {}", e.getMessage(), e);
        return build(401, MSG_AUTH_FAILED);
    }

    /**
     * Socket 超时（IOException 子类，需在 IOException 之前匹配）。
     */
    @ExceptionHandler(SocketTimeoutException.class)
    public ResponseEntity<Result<?>> handleSocketTimeout(SocketTimeoutException e) {
        log.error("MinIO 操作异常: {}", e.getMessage(), e);
        return build(504, MSG_TIMEOUT);
    }

    /**
     * 其它 IO 异常（连接被拒绝、中断等）。
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Result<?>> handleIo(IOException e) {
        log.error("MinIO 操作异常: {}", e.getMessage(), e);
        return build(504, MSG_TIMEOUT);
    }

    /**
     * 未单独映射的其它 MinIO 异常（视为服务异常）。
     */
    @ExceptionHandler(MinioException.class)
    public ResponseEntity<Result<?>> handleOtherMinio(MinioException e) {
        log.error("MinIO 操作异常: {}", e.getMessage(), e);
        return build(503, MSG_UNAVAILABLE);
    }

    /**
     * 文件上传超限：文件大小超过 {@code spring.servlet.multipart.max-file-size}（50MB），
     * 由 servlet 容器在进入 Controller 之前抛出。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<?>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("文件上传超限: {}", e.getMessage());
        return build(400, MSG_UPLOAD_TOO_LARGE);
    }

    /**
     * 参数非法（业务校验失败，如文件不存在、关联不存在），返回 400，避免被兜底误判为 500。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<?>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数校验失败: {}", e.getMessage());
        return build(400, e.getMessage());
    }

    /**
     * 请求体解析失败：JSON 语法错误、非法字符编码（如非 UTF-8 的请求体）等，
     * 由 Jackson 在进入 Controller 前抛出 {@code HttpMessageNotReadableException}。
     *
     * <p>注意：此类异常的 cause 链含 {@code JsonParseException extends IOException}，
     * 若落入 {@link #handleRuntime} 的兜底拆解会被误判为 MinIO 网络错误（504），
     * 故需在此处优先匹配并返回 400。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<?>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return build(400, MSG_INVALID_BODY);
    }

    /**
     * {@code @RequestBody @Valid} 参数校验失败（字段为 null、空白等），
     * 提取首个字段错误信息返回 400，保持统一 {@link Result} 响应格式。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<?>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", msg);
        return build(400, msg);
    }

    /**
     * MinioStorageService 将 MinIO 异常包装为 RuntimeException 抛出，
     * 此处解开 cause 链还原为具体异常并复用上述映射逻辑。
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Result<?>> handleRuntime(RuntimeException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof ErrorResponseException ere) {
                return handleErrorResponse(ere);
            }
            if (cause instanceof InvalidKeyException ike) {
                return handleInvalidKey(ike);
            }
            if (cause instanceof SocketTimeoutException ste) {
                return handleSocketTimeout(ste);
            }
            if (cause instanceof InsufficientDataException || cause instanceof InternalException
                    || cause instanceof ServerException) {
                log.error("MinIO 操作异常: {}", e.getMessage(), e);
                return build(503, MSG_UNAVAILABLE);
            }
            if (cause instanceof MinioException me) {
                return handleOtherMinio(me);
            }
            cause = cause.getCause();
        }
        log.error("系统内部异常: {}", e.getMessage(), e);
        return build(500, "系统内部错误，请稍后重试");
    }

    /**
     * 构建统一 JSON 响应，HTTP 状态码与业务 code 保持一致。
     */
    private ResponseEntity<Result<?>> build(int status, String msg) {
        return ResponseEntity.status(status).body(Result.error(status, msg));
    }
}
