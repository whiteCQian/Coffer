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
import com.coffer.file.storage.FileStoragePort;
import com.coffer.file.storage.StorageObjectNotFoundException;
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
import org.springframework.web.bind.annotation.RequestHeader;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final FileStoragePort storage;
    private final com.coffer.service.PrivateFileAccess privateFiles;
    private static final Pattern SINGLE_BYTE_RANGE = Pattern.compile("(?i)^bytes=(\\d*)-(\\d*)$");

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
            return Result.error(400, "请求参数不符合要求，请检查输入后重试");
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
            return Result.error(404, "资源不存在或已不可用");
        }
    }

    /** Authenticated, owner-scoped file content; the browser never receives an object-store URL. */
    @GetMapping("/{id}/content")
    public ResponseEntity<org.springframework.core.io.InputStreamResource> getFileContent(@PathVariable Long id,
            @RequestParam(required = false) Long revision,
            @RequestParam(defaultValue = "false") boolean download,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {
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
        FileStoragePort.StoredObject object;
        try {
            object = storage.stat(metadata.getStoragePath());
        } catch (StorageObjectNotFoundException missing) {
            return ResponseEntity.notFound().build();
        }
        if (metadata.getContentSha256() == null
                || !metadata.getContentSha256().matches("[0-9a-f]{64}")
                || !metadata.getContentSha256().equals(object.sha256())
                || !java.util.Objects.equals(metadata.getFileSize(), object.size())) {
            throw new com.coffer.file.storage.StorageConflictException("文件正文与元数据指纹不一致");
        }
        long size = object.size();
        if (size < 0) throw new IllegalStateException("存储对象大小无效");
        MediaType contentType = contentTypeFor(metadata.getFileType());
        boolean inlineSafe = contentType.getType().equals("image")
                || contentType.equals(MediaType.APPLICATION_PDF)
                || contentType.equals(MediaType.TEXT_PLAIN);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        headers.setContentDisposition(ContentDisposition.builder(inlineSafe && !download ? "inline" : "attachment")
                .filename(metadata.getFileName(), StandardCharsets.UTF_8).build());
        headers.setCacheControl("private, no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "sandbox; default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Accept-Ranges", "bytes");
        String etag = object.sha256() != null && !object.sha256().isBlank() ? object.sha256() : object.etag();
        if (etag != null && !etag.isBlank()) headers.setETag("\"" + etag.replace("\"", "") + "\"");

        ByteRange requested = null;
        if (rangeHeader != null) {
            requested = parseRange(rangeHeader, size);
            if (requested == null) {
                headers.set("Content-Range", "bytes */" + size);
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).headers(headers).build();
            }
        }

        try {
            // Open only after the owner and revision checks. The resource converter closes the stream.
            InputStream stream = requested == null
                    ? storage.readIfUnchanged(metadata.getStoragePath(), object)
                    : storage.readRangeIfUnchanged(metadata.getStoragePath(), object,
                            requested.start(), requested.length());
            headers.setContentLength(requested == null ? size : requested.length());
            if (requested != null) {
                headers.set("Content-Range", "bytes " + requested.start() + "-" + requested.end() + "/" + size);
            }
            return ResponseEntity.status(requested == null ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT)
                    .headers(headers).body(new org.springframework.core.io.InputStreamResource(stream));
        } catch (StorageObjectNotFoundException missing) {
            return ResponseEntity.notFound().build();
        }
    }

    /** One byte range only; multipart ranges are intentionally rejected. */
    private ByteRange parseRange(String value, long size) {
        if (size == 0) return null;
        Matcher match = SINGLE_BYTE_RANGE.matcher(value.trim());
        if (!match.matches()) return null;
        try {
            String first = match.group(1);
            String last = match.group(2);
            if (first.isEmpty()) {
                if (last.isEmpty()) return null;
                long suffix = Long.parseLong(last);
                if (suffix <= 0) return null;
                return new ByteRange(Math.max(0, size - suffix), size - 1);
            }
            long start = Long.parseLong(first);
            if (start < 0 || start >= size) return null;
            long end = last.isEmpty() ? size - 1 : Math.min(Long.parseLong(last), size - 1);
            return end < start ? null : new ByteRange(start, end);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private record ByteRange(long start, long end) {
        long length() { return end - start + 1; }
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
            return Result.error(400, "请求参数不符合要求，请检查输入后重试");
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
            return Result.error(400, "请求参数不符合要求，请检查输入后重试");
        }
    }

    /** Delete the logical file and enqueue durable physical cleanup. */
    @DeleteMapping("/{id}")
    public Result<Void> deleteFile(@PathVariable Long id) {
        try {
            fileLifecycleApplicationService.deleteFile(id);
            return Result.success();
        } catch (IllegalArgumentException e) {
            // 文件不存在 → 404
            log.warn("文件删除失败");
            return Result.error(404, "资源不存在或已不可用");
        }
    }

    /** Upload through the configured storage port with a client-stable retry key. */
    @PostMapping("/upload")
    @com.coffer.model.runtime.ModelSubmission("UPLOAD")
    public Result<FileUploadResponse> uploadFile(@Valid @ModelAttribute FileUploadRequest request,
                                                 @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return Result.success(fileUploadApplicationService.upload(request, idempotencyKey));
    }
}
