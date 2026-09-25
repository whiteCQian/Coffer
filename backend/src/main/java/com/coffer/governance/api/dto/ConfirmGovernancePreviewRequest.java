package com.coffer.governance.api.dto;

import lombok.Data;

import java.util.List;

/** Selective or full confirmation request for a preview batch. */
@Data
public class ConfirmGovernancePreviewRequest {

    /** Idempotency key for this confirmation request. */
    private String requestId;

    /** Item IDs selected for confirmation; ignored when confirmAll is true. */
    private List<Long> itemIds;

    /** Confirms every currently actionable READY/EDITED item in the batch. */
    private boolean confirmAll;

    public ConfirmGovernancePreviewRequest() {
    }

    /** Backward-compatible constructor kept for existing callers and tests. */
    public ConfirmGovernancePreviewRequest(List<Long> itemIds, boolean confirmAll) {
        this(null, itemIds, confirmAll);
    }

    public ConfirmGovernancePreviewRequest(String requestId, List<Long> itemIds, boolean confirmAll) {
        this.requestId = requestId;
        this.itemIds = itemIds;
        this.confirmAll = confirmAll;
    }
}
