package com.coffer.file.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件重命名请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RenameFileRequest {

    /** 新文件名，必填；去空白后长度上限 255 由服务端校验。 */
    @NotBlank(message = "文件名不能为空")
    private String fileName;
}
