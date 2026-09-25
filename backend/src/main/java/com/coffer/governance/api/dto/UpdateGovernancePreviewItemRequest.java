package com.coffer.governance.api.dto;

import com.coffer.file.domain.CategoryType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** User-edited suggestion values for one preview item. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateGovernancePreviewItemRequest {

    @NotBlank
    private String suggestedFileName;

    @NotNull
    private CategoryType suggestedCategory;

    private String suggestedSummary;

    @NotEmpty
    private List<@NotBlank String> suggestedTags;
}
