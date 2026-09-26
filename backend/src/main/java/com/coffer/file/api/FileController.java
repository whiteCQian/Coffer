package com.coffer.file.api;

import com.coffer.file.api.dto.CategoryCountResponse;
import com.coffer.file.api.dto.FileDetailResponse;
import com.coffer.file.api.dto.FileListResponse;
import com.coffer.file.api.dto.FileUploadRequest;
import com.coffer.file.api.dto.FileUploadResponse;
import com.coffer.file.api.dto.RenameFileRequest;
import com.coffer.dto.Result;
import com.coffer.file.application.FileService;
import com.coffer.file.application.FileUploadApplicationService;
import com.coffer.file.application.FileLifecycleApplicationService;
import com.coffer.file.domain.FileMetadata;
import com.coffer.service.MinioStorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.Locale;

/**
 * 文件接口：列表查询与上传。
 */
@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;
    private final FileUploadApplicationService fileUploadApplicationService;
    private final FileLifecycleApplicationService fileLifecycleApplicationService;
    private final MinioStorageService minioStorageService;
    private final com.coffer.service.PrivateFileAccess privateFiles;

    /**
     * 文件列表查询：可选关键词（文件名/AI摘要/未拒绝标签模糊匹配）、可选分类过滤、
     * 可选标签筛选（点选标签芯片）、可选排序（new/old/size/name）与分页。
     *
     * @param keyword  搜索关键词，可选，为空则不约束
     * @param category 分类过滤（枚举名），可选，为空则不约束（tag 非空时忽略）
     * @param tag      标签名（匹配已确认/待确认标签），可选，为空则不约束
     * @param sort     排序 token（new/old/size/name），可选，缺省按最新优先
     * @param pageable 分页参数（默认每页 10 条）
     * @return 统一响应，data 为分页文件列表（含 tagStatus 标签汇总确认状态）；非法排序/分类时 code=400
     */
    @GetMapping
    public Result<Page<FileListResponse>> listFiles(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String sort,
            @PageableDefault(size = 10) Pageable pageable) {
        log.info("查询文件列表 page={}, size={}", pageable.getPageNumber(), pageable.getPageSize());
        try {
            return Result.success(fileService.listFiles(keyword, category, tag, sort, pageable));
        } catch (IllegalArgumentException e) {
            // 非法排序 token / 非法分类参数 → 400
            log.warn("文件列表查询参数非法");
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 分类计数：返回各分类下文件数量（仅 count>0，按数量倒序），供前端分类徽标展示。
     *
     * @return 成功响应，data 为分类计数列表
     */
    @GetMapping("/categories")
    public Result<List<CategoryCountResponse>> listCategories() {
        return fileService.listCategories();
    }

    /**
     * 文件详情查询：基础信息 + 标签列表（含确认状态）。
     *
     * @param id 文件 ID
     * @return 统一响应；文件不存在时 code=404
     */
    @GetMapping("/{id}")
    public Result<FileDetailResponse> getFileDetail(@PathVariable Long id,
            @RequestParam(required = false) Long revision) {
        try {
            if (revision != null) privateFiles.requireVersion(id, revision);
            FileDetailResponse detail = fileService.getFileDetail(id);
            if (revision != null) {
                privateFiles.requireVersion(id, revision);
                detail.setPreviewUrl("/api/files/" + id + "/content?revision=" + revision);
            }
            return Result.success(detail);
        } catch (IllegalArgumentException e) {
            // 文件不存在 → 404
            log.warn("文件详情查询失败");
            return Result.error(404, e.getMessage());
        }
    }

    /** Authenticated, owner-scoped file content; the browser never receives an object-store URL. */
    @GetMapping("/{id}/content")
    public ResponseEntity<org.springframework.core.io.InputStreamResource> getFileContent(@PathVariable Long id,
            @RequestParam(required = false) Long revision) {
        FileMetadata metadata;
        try {
            metadata = fileService.requireFileForContent(id);
            if (revision != null) privateFiles.requireVersion(id, revision);
        } catch (IllegalArgumentException missingOrNotOwned) {
            return ResponseEntity.notFound().build();
        }
        if (metadata.getStoragePath() == null || metadata.getStoragePath().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        MediaType contentType = contentTypeFor(metadata.getFileType());
        boolean inlineSafe = contentType.getType().equals("image")
                || contentType.equals(MediaType.APPLICATION_PDF)
                || contentType.equals(MediaType.TEXT_PLAIN);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        if (metadata.getFileSize() != null) headers.setContentLength(metadata.getFileSize());
        headers.setContentDisposition(ContentDisposition.builder(inlineSafe ? "inline" : "attachment")
                .filename(metadata.getFileName(), StandardCharsets.UTF_8).build());
        headers.setCacheControl("private, no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "sandbox; default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'");
        // Open under the authenticated request scope. ResourceHttpMessageConverter closes the stream.
        return ResponseEntity.ok().headers(headers).body(new org.springframework.core.io.InputStreamResource(
                minioStorageService.getFileStream(null, metadata.getStoragePath())));
    }

    private MediaType contentTypeFor(String fileType) {
        String extension = fileType == null ? "" : fileType.trim().toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "png" -> MediaType.IMAGE_PNG;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> MediaType.parseMediaType("image/webp");
            case "bmp" -> MediaType.parseMediaType("image/bmp");
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "txt" -> MediaType.TEXT_PLAIN;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    /**
     * 文件重命名：仅改 {@code fileName}，返回重命名后的完整详情。
     *
     * @param id      文件 ID
     * @param request 重命名请求（新文件名必填，去空白后 ≤255 字符）
     * @return 统一响应，data 为最新文件详情；文件名非法或文件不存在时 code=400
     */
    @PatchMapping("/{id}")
    public Result<FileDetailResponse> renameFile(@PathVariable Long id,
                                                 @RequestBody @Valid RenameFileRequest request) {
        try {
            fileLifecycleApplicationService.renameFile(id, request.getFileName());
            // 操作事务已提交，读取最新详情返回前端
            return Result.success(fileService.getFileDetail(id));
        } catch (IllegalArgumentException e) {
            // 文件名非法 / 文件不存在 → 400
            log.warn("文件重命名失败");
            return Result.error(400, e.getMessage());
        }
    }

    /** Direct category writes were retired because they bypass the preview and archive ledger. */
    @PutMapping("/{id}/category")
    public ResponseEntity<Result<Void>> changeCategory(@PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.<Void>error(409, "分类变更必须通过整理预览确认"));
    }

    /**
     * 失败文件重试：仅 {@code FileStatus.FAILED} 可重试。事务内删除旧任务并重建 PENDING
     * 任务、文件回 PENDING，事务提交后触发既有分析管道（从原 storagePath 重读）。
     *
     * @param id 文件 ID
     * @return 统一响应；文件不存在或状态非 FAILED 时 code=400
     */
    @PostMapping("/{id}/retry")
    @com.coffer.model.runtime.ModelSubmission("RETRY")
    public Result<Void> retryFile(@PathVariable Long id) {
        try {
            fileLifecycleApplicationService.retryFile(id);
            return Result.success();
        } catch (IllegalArgumentException e) {
            // 文件不存在 / 非失败状态 → 400
            log.warn("文件重试失败");
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 删除文件（允许任意状态）：清理 DB 记录并删除 MinIO 对象。
     *
     * <p>DB 清理失败整体回滚、对象不动；DB 删成功后 MinIO 删除失败仅留孤儿对象（可后台 GC），
     * 只记日志不返回错误。正在跑的异步线程因查不到记录自然退出。
     *
     * @param id 文件 ID
     * @return 统一响应；文件不存在时 code=404
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteFile(@PathVariable Long id) {
        try {
            fileLifecycleApplicationService.deleteFile(id);
            return Result.success();
        } catch (IllegalArgumentException e) {
            // 文件不存在 → 404
            log.warn("文件删除失败");
            return Result.error(404, e.getMessage());
        }
    }

    /**
     * 文件上传：接收 multipart/form-data 文件，同步上传 MinIO 并登记异步任务。
     *
     * <p>流程：{@code @Valid} 校验文件非空且 ≤50MB → 生成存储路径 → 上传 MinIO →
     * 事务内登记 FileMetadata + AsyncTask（{@code registerUploadTask}）→ 事务提交后
     * 异步触发分析管道 → 返回含 taskId 的响应供前端轮询。Controller 不加
     * {@code @Transactional}，避免异步线程读到未提交数据。
     *
     * @param request 上传请求（file 必填，sessionId 可选）
     * @return 统一响应，data 为任务信息（taskId/fileName/fileSize/status/uploadTime）
     */
    @PostMapping("/upload")
    @com.coffer.model.runtime.ModelSubmission("UPLOAD")
    public Result<FileUploadResponse> uploadFile(@Valid @ModelAttribute FileUploadRequest request) {
        try {
            return Result.success(fileUploadApplicationService.upload(request));
        } catch (Exception e) {
            log.error("文件上传失败（其他异常），异常类型={}", e.getClass().getSimpleName());
            return Result.error(500, "文件上传失败");
        }
    }
}
