package com.coffer.governance.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request to create a fresh preview from a previous preview's file set. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegenerateGovernancePreviewRequest {

    @NotBlank
    private String requestId;
}
