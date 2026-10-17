package com.coffer.file.application;

import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** One projection of durable facts for lists, details and pending counts. */
@Service @RequiredArgsConstructor @com.coffer.auth.service.OwnerOnly
public class FileTaskState {
    private final ArchiveOperationItemRepository operations;
    public String state(FileMetadata file) {
        var latest = operations.findFirstByFileIdOrderByCreatedAtDescIdDesc(file.getId()).orElse(null);
        if (latest != null && latest.getRollbackStatus() != com.coffer.governance.domain.ArchiveOperationItemRollbackStatus.SUCCEEDED) {
            switch (latest.getRollbackStatus()) {
                case PENDING, VALIDATING, COPYING, DB_COMMITTING, CLEANUP_PENDING -> { return "ROLLING_BACK"; }
                case FAILED, CONFLICTED -> { return "ARCHIVE_BLOCKED"; }
                default -> { }
            }
            switch (latest.getExecutionStatus()) {
                case PENDING, VALIDATING, COPYING, DB_COMMITTING -> { return "ARCHIVING"; }
                case FAILED -> { return "ARCHIVE_FAILED"; }
                case MANUAL_REVIEW, CONFLICTED -> { return "ARCHIVE_BLOCKED"; }
                default -> { }
            }
        }
        if (file.isArchived()) return "ARCHIVED";
        if (file.getStatus() == FileStatus.FAILED) return "SAVED_NEEDS_ATTENTION";
        if (file.getStatus() == FileStatus.COMPLETED) return "AWAITING_CONFIRMATION";
        return "SAVED_ANALYZING";
    }
}
