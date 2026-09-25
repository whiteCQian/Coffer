package com.coffer.file.api.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传请求 DTO。
 *
 * <p>关联上传的对话会话（sessionId 可选），文件大小校验使用 @AssertTrue 自定义校验，
 * 因为 Bean Validation 的 @Size 仅支持 CharSequence/Collection/Map/数组，不适用于 MultipartFile。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileUploadRequest {

    /** 单文件大小上限：50MB。 */
    public static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

    /** 上传文件，不允许为空。 */
    @NotNull(message = "上传文件不能为空")
    private MultipartFile file;

    /** 关联上传时的对话上下文（可选）。 */
    private String sessionId;

    /**
     * 文件大小校验（替代 @Size）。
     *
     * @return 文件为空或未超过 50MB 时返回 true
     */
    @AssertTrue(message = "文件大小不能超过50MB")
    public boolean isFileSizeValid() {
        return file == null || file.getSize() <= MAX_FILE_SIZE;
    }
}
