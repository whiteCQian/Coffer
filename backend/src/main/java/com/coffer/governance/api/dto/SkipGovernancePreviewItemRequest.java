package com.coffer.governance.api.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Optional user reason for skipping one preview item. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkipGovernancePreviewItemRequest {

    @Size(max = 255)
    private String reason;
}
