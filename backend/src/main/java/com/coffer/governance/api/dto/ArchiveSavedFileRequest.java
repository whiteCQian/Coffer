package com.coffer.governance.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Optional edits apply atomically with confirmation; null means use the saved suggestion. */
public record ArchiveSavedFileRequest(@NotNull Long revision,
        @Size(max = 255) String fileName, @Size(max = 1000) String summary,
        String category, @Size(max = 50) List<@Size(max = 100) String> tags) { }
