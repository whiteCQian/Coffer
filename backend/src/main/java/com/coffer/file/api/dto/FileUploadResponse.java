package com.coffer.file.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文件上传响应 DTO。
 *
 * <p>上传接口同步返回任务 ID（taskId 必填），前端拿到后即可轮询异步任务进度。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileUploadResponse {

    /** 异步任务 ID，必填，用于前端轮询任务进度。 */
    @NotNull(message = "任务ID不能为空")
    private String taskId;

    /** 文件名，回显给前端。 */
    private String fileName;

    /** 文件大小（字节）。 */
    private Long fileSize;

    /** 初始任务状态。 */
    @Builder.Default
    private String status = "PENDING";

    /** 上传时间。 */
    private LocalDateTime uploadTime;
}
