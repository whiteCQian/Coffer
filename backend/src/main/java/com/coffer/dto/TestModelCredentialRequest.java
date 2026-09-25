package com.coffer.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TestModelCredentialRequest {
    @NotBlank(message = "模型密钥不能为空")
    private String apiKey;
}
