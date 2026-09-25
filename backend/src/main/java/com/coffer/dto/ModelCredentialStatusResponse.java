package com.coffer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelCredentialStatusResponse {
    @Schema(description = "各模型是否已配置密钥")
    private Map<String, Boolean> configured;
    @Schema(description = "各模型的脱敏密钥")
    private Map<String, String> maskedApiKeys;
    private boolean restartRequired;
}
