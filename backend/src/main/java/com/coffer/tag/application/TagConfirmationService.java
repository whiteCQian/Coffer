package com.coffer.tag.application;

import com.coffer.tag.domain.ConfirmationStatus;
import com.coffer.tag.domain.FileTagMapping;
import com.coffer.tag.domain.Tag;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.tag.infrastructure.persistence.TagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 标签确认服务：实现「人在回路」中用户对系统自动生成标签的确认与拒绝操作。
 *
 * <p>确认：将 {@link FileTagMapping} 从待确认（PENDING_CONFIRMATION）流转为已确认（CONFIRMED）；
 * 拒绝：流转为已拒绝（REJECTED）并可选携带修正标签——修正标签自动挂回文件，
 * 再次以待确认（PENDING_CONFIRMATION）状态进入人工确认。
 */
@Slf4j
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class TagConfirmationService {

    private final FileTagMappingRepository fileTagMappingRepository;
    private final TagRepository tagRepository;
    private final com.coffer.file.infrastructure.persistence.FileMetadataRepository files;

    private void advanceRevision(Long fileId) {
        var file = files.findById(fileId).orElseThrow(com.coffer.auth.service.ResourceNotFoundException::new);
        file.setRevision(file.getRevision() + 1);
        file.setVectorIndexedAt(null);
        files.save(file);
    }

    /**
     * 确认文件上的指定标签。
     *
     * @param fileId 文件 ID
     * @param tagId  标签 ID
     * @throws IllegalArgumentException 参数为空或关联记录不存在时抛出
     */
    @Transactional
    public void confirmTag(Long fileId, Long tagId) {
        if (fileId == null || tagId == null) {
            throw new IllegalArgumentException("文件 ID 与标签 ID 不能为空");
        }

        FileTagMapping mapping = fileTagMappingRepository.findByFileIdAndTagId(fileId, tagId)
                .orElseThrow(() -> new com.coffer.auth.service.ResourceNotFoundException());

        // 已确认则幂等返回，避免重复更新确认时间
        if (mapping.getConfirmationStatus() == ConfirmationStatus.CONFIRMED) {
            log.info("标签已确认，幂等返回 fileId={}, tagId={}", fileId, tagId);
            return;
        }

        mapping.setConfirmationStatus(ConfirmationStatus.CONFIRMED);
        mapping.setConfirmedAt(LocalDateTime.now());
        fileTagMappingRepository.save(mapping);
        advanceRevision(fileId);
        log.info("标签确认完成 fileId={}, tagId={}", fileId, tagId);

    }

    /**
     * 拒绝文件上的指定标签，可选携带修正标签。
     *
     * <p>原关联流转为 REJECTED，用户修正意图写入 {@code confirmationNote}；
     * 若 {@code newTagName} 非空，则查找或创建该标签并建立新的文件-标签关联
     * （状态 PENDING_CONFIRMATION，需再次人工确认）。整个过程事务原子，任一异常全部回滚。
     *
     * @param fileId     文件 ID
     * @param tagId      待拒绝的标签 ID
     * @param newTagName 修正标签名称（可空；空则仅拒绝不新建）
     * @throws IllegalArgumentException 参数为空或关联记录不存在时抛出
     */
    @Transactional
    public void rejectTag(Long fileId, Long tagId, String newTagName) {
        if (fileId == null || tagId == null) {
            throw new IllegalArgumentException("文件 ID 与标签 ID 不能为空");
        }

        FileTagMapping mapping = fileTagMappingRepository.findByFileIdAndTagId(fileId, tagId)
                .orElseThrow(() -> new com.coffer.auth.service.ResourceNotFoundException());

        // 已拒绝则幂等返回，避免重复更新确认时间与备注
        if (mapping.getConfirmationStatus() == ConfirmationStatus.REJECTED) {
            log.info("标签已拒绝，幂等返回 fileId={}, tagId={}", fileId, tagId);
            return;
        }

        // 1. 拒绝原关联：记录修正意图到 confirmationNote
        String normalizedNewTag = (newTagName == null || newTagName.isBlank()) ? null : newTagName.trim();
        mapping.setConfirmationStatus(ConfirmationStatus.REJECTED);
        mapping.setConfirmedAt(LocalDateTime.now());
        mapping.setConfirmationNote(normalizedNewTag);
        fileTagMappingRepository.save(mapping);
        advanceRevision(fileId);

        // 2. 携带修正标签：查找或创建 Tag，并建立待确认的新关联（重复关联则跳过）
        if (normalizedNewTag != null) {
            Tag newTag = tagRepository.findByTagName(normalizedNewTag)
                    .orElseGet(() -> tagRepository.save(Tag.builder().tagName(normalizedNewTag).build()));
            if (!fileTagMappingRepository.existsByFileIdAndTagId(fileId, newTag.getId())) {
                FileTagMapping correction = FileTagMapping.builder()
                        .fileId(fileId)
                        .tagId(newTag.getId())
                        .build();
                correction.setConfirmationStatus(ConfirmationStatus.PENDING_CONFIRMATION);
                fileTagMappingRepository.save(correction);
                log.info("修正标签已挂回待确认 fileId={}, newTagId={}", fileId, newTag.getId());
            }
        }

        log.info("标签已拒绝 fileId={}, tagId={}", fileId, tagId);
    }
}
