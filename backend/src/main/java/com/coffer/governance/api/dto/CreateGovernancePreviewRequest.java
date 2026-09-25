package com.coffer.governance.api.dto;

import com.coffer.governance.domain.GovernancePreviewSource;
import com.coffer.governance.domain.GovernanceRunMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Request to synchronously build a dry-run preview for existing files. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateGovernancePreviewRequest {

    @NotEmpty
    private List<Long> fileIds;

    /** Client-generated idempotency key. */
    @NotBlank
    private String requestId;

    private GovernancePreviewSource source = GovernancePreviewSource.UPLOAD;

    /** Optional request hint; the validated global runtime mode is authoritative. */
    private GovernanceRunMode mode;
}
