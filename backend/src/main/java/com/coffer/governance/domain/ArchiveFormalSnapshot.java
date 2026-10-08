package com.coffer.governance.domain;

import com.coffer.tag.domain.ConfirmationStatus;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** An immutable, content-addressed view of every formal file field affected by governance. */
public record ArchiveFormalSnapshot(String fileName, String path, String category,
                                    String summary, List<TagState> tags, boolean archived,
                                    long revision, String sha256, long size, String etag) {
    public ArchiveFormalSnapshot {
        if (fileName == null || fileName.isBlank() || path == null || path.isBlank()
                || sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")
                || revision < 0 || size < 0 || tags == null) {
            throw new IllegalArgumentException("归档快照缺少有效的正式字段或内容摘要");
        }
        tags = List.copyOf(tags);
        Set<String> names = new HashSet<>();
        for (TagState tag : tags) {
            if (tag == null || tag.name() == null || tag.name().isBlank()
                    || tag.status() == null || !names.add(tag.name())) {
                throw new IllegalArgumentException("归档快照标签无效或重复");
            }
        }
    }

    public List<String> confirmedTagNames() {
        return tags.stream().filter(t -> t.status() == ConfirmationStatus.CONFIRMED)
                .map(TagState::name).toList();
    }

    /** Confirmation notes are formal tag state; confirmation timestamps are audit events, not the tag set. */
    public record TagState(String name, ConfirmationStatus status, String note) {
        public TagState {
            Objects.requireNonNull(status, "status");
        }
    }
}
