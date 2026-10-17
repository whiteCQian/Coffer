package com.coffer.governance.application;

import com.coffer.file.domain.*;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.governance.api.dto.*;
import com.coffer.governance.domain.*;
import com.coffer.governance.infrastructure.persistence.*;
import com.coffer.tag.domain.ConfirmationStatus;
import com.coffer.tag.infrastructure.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

/** Reuses persisted analysis. This operation never calls a model or grants content consent. */
@Service @RequiredArgsConstructor @com.coffer.auth.service.OwnerOnly
public class ArchiveSavedFileService {
    private final FileMetadataRepository files;
    private final FileTagMappingRepository mappings;
    private final TagRepository tags;
    private final GovernancePreviewBatchRepository batches;
    private final GovernancePreviewItemRepository items;
    private final ArchiveOperationItemRepository operations;
    private final GovernancePreviewService previews;
    private final ArchiveObjectNameService names;
    private final FileStoragePort storage;
    private final ObjectMapper json;

    @Transactional
    public ArchiveOperationBatchResponse confirm(Long fileId, ArchiveSavedFileRequest request) {
        var file = files.findByIdForUpdate(fileId)
                .orElseThrow(com.coffer.auth.service.ResourceNotFoundException::new);
        String key = "saved-file-" + fileId + "-" + request.revision();
        var existing = batches.findByRequestId(key).orElse(null);
        if (existing != null) {
            var result = previews.get(existing.getPreviewId()).latestOperation();
            if (result != null) return result;
            throw new IllegalArgumentException("已有确认未完成，请在最近操作查看结果");
        }
        if (file.isArchived()) throw new IllegalArgumentException("文件已归档，无需重复确认");
        if (!Objects.equals(file.getRevision(), request.revision()))
            throw new IllegalArgumentException("建议版本已变化，请重新打开文件确认");
        if (file.getStatus() == FileStatus.PROCESSING || file.getStatus() == FileStatus.PENDING)
            throw new IllegalArgumentException("正在整理，请稍后确认；文件已保存，可取回");
        // A different client/revision must not start another operation while one is active.
        if (operations.findByFileIdOrderByCreatedAtDesc(fileId).stream().anyMatch(i ->
                !List.of(ArchiveOperationItemRollbackStatus.NOT_REQUESTED,
                        ArchiveOperationItemRollbackStatus.SUCCEEDED,
                        ArchiveOperationItemRollbackStatus.NOT_REVERSIBLE).contains(i.getRollbackStatus())
                || (!List.of(ArchiveOperationItemExecutionStatus.SUCCEEDED,
                        ArchiveOperationItemExecutionStatus.SKIPPED).contains(i.getExecutionStatus())
                        && i.getRollbackStatus() != ArchiveOperationItemRollbackStatus.SUCCEEDED)))
            throw new IllegalArgumentException("已有归档任务，请在最近操作重试或处理");
        var now = LocalDateTime.now();
        var batch = GovernancePreviewBatch.builder().previewId(UUID.randomUUID().toString())
                .requestId(key).source(GovernancePreviewSource.UPLOAD).runMode(GovernanceRunMode.API)
                .status(GovernancePreviewBatchStatus.READY).totalCount(1).readyCount(1)
                .createdAt(now).updatedAt(now).expiresAt(now.plusDays(1)).build();
        batches.saveAndFlush(batch);
        String name = request.fileName() == null ? file.getFileName() : request.fileName().trim();
        if (name.isBlank() || name.contains("/") || name.contains("\\"))
            throw new IllegalArgumentException("文件名不能为空或含路径分隔符");
        CategoryType category = request.category() == null ? file.getCategory()
                : CategoryType.fromLabel(request.category());
        if (category == null) category = CategoryType.OTHER;
        List<String> selectedTags = request.tags();
        if (selectedTags == null) {
            var ids = mappings.findByFileId(fileId).stream()
                    .filter(m -> m.getConfirmationStatus() != ConfirmationStatus.REJECTED)
                    .map(m -> m.getTagId()).toList();
            selectedTags = tags.findAllById(ids).stream().map(t -> t.getTagName()).toList();
        }
        var object = storage.stat(file.getStoragePath());
        String encoded;
        try { encoded = json.writeValueAsString(selectedTags.stream().filter(Objects::nonNull)
                .map(String::trim).filter(t -> !t.isBlank()).distinct().toList()); }
        catch (Exception failure) { throw new IllegalArgumentException("标签格式无效"); }
        items.saveAndFlush(GovernancePreviewItem.builder().previewId(batch.getPreviewId()).fileId(fileId)
                .sourceRevision(request.revision()).sourcePath(file.getStoragePath())
                .sourceFileName(file.getFileName()).sourceCategory(file.getCategory() == null ? "OTHER" : file.getCategory().name())
                .sourceEtag(object.etag()).sourceSize(object.size()).suggestedFileName(name)
                .suggestedPath(names.generateArchivePath(category, name, file.getUploadTime()))
                .suggestedCategory(category.name()).suggestedTags(encoded)
                .suggestedSummary(request.summary() == null ? file.getSummary() : request.summary())
                .analysisMode(GovernanceRunMode.API).analysisModel("SAVED_SUGGESTION")
                .status(GovernancePreviewItemStatus.READY).createdAt(now).updatedAt(now).build());
        var confirmed = previews.confirm(batch.getPreviewId(),
                new ConfirmGovernancePreviewRequest(key, List.of(), true));
        if (confirmed.latestOperation() == null)
            throw new IllegalArgumentException("文件已保存，但归档未受理，请重新打开后重试");
        return confirmed.latestOperation();
    }
}
