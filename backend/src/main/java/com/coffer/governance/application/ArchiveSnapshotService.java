package com.coffer.governance.application;

import com.coffer.file.domain.FileMetadata;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.governance.domain.ArchiveFormalSnapshot;
import com.coffer.governance.domain.ArchiveOperationItem;
import com.coffer.tag.domain.FileTagMapping;
import com.coffer.tag.domain.Tag;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.tag.infrastructure.persistence.TagRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Captures and checks the formal metadata/tag state saved in an archive ledger item. */
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class ArchiveSnapshotService {
    public static final int VERSION = 1;

    private final FileTagMappingRepository mappings;
    private final TagRepository tags;
    private final ObjectMapper mapper;

    public ArchiveFormalSnapshot capture(FileMetadata file, FileStoragePort.StoredObject object) {
        if (file.getFileSize() == null || file.getFileSize() != object.size()) {
            throw new ArchiveExecutionConflictException("正式文件长度与对象不一致");
        }
        return new ArchiveFormalSnapshot(file.getFileName(), file.getStoragePath(),
                file.getCategory() == null ? null : file.getCategory().name(), file.getSummary(),
                currentTags(file.getId()), file.isArchived(), value(file.getRevision()),
                object.sha256(), object.size(), object.etag());
    }

    public ArchiveFormalSnapshot intendedTarget(ArchiveFormalSnapshot source, String fileName,
                                                 String path, String category, String summary,
                                                 List<String> confirmedNames) {
        if (confirmedNames == null) throw new IllegalArgumentException("缺少确认标签集合");
        List<ArchiveFormalSnapshot.TagState> targetTags = confirmedNames.stream()
                .map(name -> new ArchiveFormalSnapshot.TagState(name,
                        com.coffer.tag.domain.ConfirmationStatus.CONFIRMED, null)).toList();
        return new ArchiveFormalSnapshot(fileName, path, category,
                summary == null ? source.summary() : summary, targetTags, true,
                source.revision() + 1, source.sha256(), source.size(), null);
    }

    public String encode(ArchiveFormalSnapshot snapshot) {
        try { return mapper.writeValueAsString(snapshot); }
        catch (JsonProcessingException e) { throw new IllegalStateException("无法写入归档正式快照", e); }
    }

    /** Null means a legacy ledger predating complete snapshots; it must not claim full rollback. */
    public ArchiveFormalSnapshot sourceOrNull(ArchiveOperationItem item) {
        return decode(item.getSnapshotVersion(), item.getSourceSnapshotJson());
    }

    public ArchiveFormalSnapshot targetOrNull(ArchiveOperationItem item) {
        return decode(item.getSnapshotVersion(), item.getTargetSnapshotJson());
    }

    public ArchiveFormalSnapshot requireSource(ArchiveOperationItem item) {
        ArchiveFormalSnapshot snapshot = sourceOrNull(item);
        if (snapshot == null) throw new ArchiveRollbackNotReversibleException("旧台账缺少完整原快照，只能人工部分恢复");
        return snapshot;
    }

    public ArchiveFormalSnapshot requireTarget(ArchiveOperationItem item) {
        ArchiveFormalSnapshot snapshot = targetOrNull(item);
        if (snapshot == null) throw new ArchiveRollbackNotReversibleException("旧台账缺少完整目标快照，只能人工部分恢复");
        return snapshot;
    }

    public boolean matches(FileMetadata file, ArchiveFormalSnapshot snapshot) {
        return value(file.getRevision()) == snapshot.revision()
                && Objects.equals(file.getFileName(), snapshot.fileName())
                && Objects.equals(file.getStoragePath(), snapshot.path())
                && Objects.equals(file.getCategory() == null ? null : file.getCategory().name(), snapshot.category())
                && Objects.equals(file.getSummary(), snapshot.summary())
                && file.isArchived() == snapshot.archived()
                && Objects.equals(file.getFileSize(), snapshot.size())
                && new HashSet<>(currentTags(file.getId())).equals(new HashSet<>(snapshot.tags()));
    }

    public boolean matchesAfterRollback(FileMetadata file, ArchiveFormalSnapshot source, Long rollbackRevision) {
        if (rollbackRevision == null) return false;
        return matches(file, new ArchiveFormalSnapshot(source.fileName(), source.path(), source.category(),
                source.summary(), source.tags(), source.archived(), rollbackRevision,
                source.sha256(), source.size(), source.etag()));
    }

    public void replaceTags(Long fileId, List<ArchiveFormalSnapshot.TagState> desired) {
        mappings.deleteByFileId(fileId, com.coffer.auth.service.TenantContext.requireOwnerId());
        for (ArchiveFormalSnapshot.TagState state : desired) {
            Tag tag = tags.findByTagName(state.name())
                    .orElseGet(() -> tags.save(Tag.builder().tagName(state.name()).build()));
            mappings.save(FileTagMapping.builder().fileId(fileId).tagId(tag.getId())
                    .confirmationStatus(state.status())
                    .confirmedAt(state.status() == com.coffer.tag.domain.ConfirmationStatus.PENDING_CONFIRMATION
                            ? null : LocalDateTime.now())
                    .confirmationNote(state.note()).build());
        }
        mappings.flush();
        if (!new HashSet<>(currentTags(fileId)).equals(new HashSet<>(desired))) {
            throw new IllegalStateException("归档正式标签集合校验失败");
        }
    }

    public List<ArchiveFormalSnapshot.TagState> currentTags(Long fileId) {
        List<FileTagMapping> associations = mappings.findByFileId(fileId);
        if (associations.isEmpty()) return List.of();
        Map<Long, Tag> byId = new HashMap<>();
        tags.findAllById(associations.stream().map(FileTagMapping::getTagId).toList())
                .forEach(tag -> byId.put(tag.getId(), tag));
        return associations.stream().map(mapping -> {
            Tag tag = byId.get(mapping.getTagId());
            if (tag == null) throw new IllegalStateException("正式标签字典缺失");
            return new ArchiveFormalSnapshot.TagState(tag.getTagName(),
                    mapping.getConfirmationStatus(), mapping.getConfirmationNote());
        }).toList();
    }

    private ArchiveFormalSnapshot decode(Integer version, String json) {
        if (version == null) return null;
        if (version != VERSION || json == null || json.isBlank()) {
            throw new IllegalStateException("归档快照版本或内容无效");
        }
        try { return mapper.readValue(json, ArchiveFormalSnapshot.class); }
        catch (Exception e) { throw new IllegalStateException("归档正式快照损坏", e); }
    }

    private long value(Long revision) { return revision == null ? 0L : revision; }
}
