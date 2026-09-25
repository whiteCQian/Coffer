package com.coffer.file.application.assembler;

import com.coffer.file.api.dto.FileDetailResponse;
import com.coffer.file.api.dto.FileListResponse;
import com.coffer.file.domain.FileMetadata;
import com.coffer.tag.api.dto.FileTagInfo;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Converts file-domain data into the stable HTTP response models.
 *
 * <p>Keeping this conversion outside the DTO prevents API models from depending
 * on JPA/domain entities while preserving the existing JSON contract.</p>
 */
@Component
public class FileResponseAssembler {

    public FileListResponse toListResponse(FileMetadata metadata, String tagStatus,
                                           List<FileTagInfo> tags) {
        return FileListResponse.builder()
                .id(metadata.getId())
                .fileName(metadata.getFileName())
                .fileType(metadata.getFileType())
                .fileSize(metadata.getFileSize())
                .uploadTime(metadata.getUploadTime())
                .summary(metadata.getSummary())
                .category(metadata.getCategory() == null ? null : metadata.getCategory().name())
                .status(metadata.getStatus() == null ? null : metadata.getStatus().name())
                .archived(metadata.isArchived())
                .tagStatus(tagStatus)
                .tags(tags)
                .build();
    }

    public FileDetailResponse toDetailResponse(FileMetadata metadata, String tagStatus,
                                                List<FileTagInfo> confirmedTags,
                                                List<FileTagInfo> pendingTags,
                                                String previewUrl) {
        return FileDetailResponse.builder()
                .id(metadata.getId())
                .fileName(metadata.getFileName())
                .fileType(metadata.getFileType())
                .fileSize(metadata.getFileSize())
                .uploadTime(metadata.getUploadTime())
                .summary(metadata.getSummary())
                .status(metadata.getStatus() == null ? null : metadata.getStatus().name())
                .tagStatus(tagStatus)
                .confirmedTags(confirmedTags)
                .pendingTags(pendingTags)
                .previewUrl(previewUrl)
                .category(metadata.getCategory() == null ? null : metadata.getCategory().name())
                .archived(metadata.isArchived())
                .storagePath(metadata.getStoragePath())
                .build();
    }
}
