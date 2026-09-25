package com.coffer.governance.api.dto;

import com.coffer.governance.domain.GovernanceRunMode;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Single-file re-analysis request; the result is still a new dry-run preview. */
@Data
@NoArgsConstructor
public class ReanalyzeGovernanceFileRequest {
    @NotBlank
    private String requestId;

    private GovernanceRunMode mode = GovernanceRunMode.API;
}
