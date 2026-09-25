package com.coffer.governance.api.dto;

import com.coffer.governance.domain.GovernanceRunMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Request to re-analyze one or more existing files into a fresh dry-run preview. */
@Data
@NoArgsConstructor
public class ReanalyzeGovernanceFilesRequest {
    @NotEmpty
    private List<Long> fileIds;

    @NotBlank
    private String requestId;

    /** Optional request hint; the validated global runtime mode is authoritative. */
    private GovernanceRunMode mode;
}
