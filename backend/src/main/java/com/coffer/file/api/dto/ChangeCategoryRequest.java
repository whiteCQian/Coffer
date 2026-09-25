package com.coffer.file.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件改分类请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangeCategoryRequest {

    /** 目标分类（受控枚举名），必填；严格解析由服务端校验。 */
    @NotBlank(message = "分类不能为空")
    private String category;
}
