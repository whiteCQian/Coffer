package com.coffer.tag.application;

import com.coffer.file.application.archive.StorageArchiveService;
import com.coffer.tag.domain.ConfirmationStatus;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.tag.domain.FileTagMapping;
import com.coffer.tag.domain.Tag;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.tag.infrastructure.persistence.TagRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 标签确认 → 归档触发（AFTER_COMMIT）集成测试。
 *
 * <p>区别于 {@link TagConfirmationServiceTest}（类级 {@code @Transactional} 回滚，事务不提交、
 * 事件不触发），本类不标注事务：{@code confirmTag} 真实提交后，
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 应调用 {@link StorageArchiveService#archive}。
 * {@link StorageArchiveService} 以 Mock 替换，仅验证「确认 → 提交 → 触发归档」的关系。
 */
@SpringBootTest
class TagConfirmationEventTest {

    @Autowired
    private TagConfirmationService tagConfirmationService;

    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private FileTagMappingRepository fileTagMappingRepository;

    @MockitoBean
    private StorageArchiveService storageArchiveService;

    @Test
    void confirmTagTriggersArchiveAfterCommit() {
        FileMetadata fm = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("归档事件测试.txt").fileSize(1L).fileType("txt")
                .status(FileStatus.COMPLETED).build());
        Tag tag = tagRepository.save(Tag.builder().tagName("事件归档标签").build());
        fileTagMappingRepository.save(FileTagMapping.builder()
                .fileId(fm.getId()).tagId(tag.getId())
                .confirmationStatus(ConfirmationStatus.PENDING_CONFIRMATION).build());

        tagConfirmationService.confirmTag(fm.getId(), tag.getId());

        // 事务已提交 → AFTER_COMMIT 监听器触发归档
        verify(storageArchiveService).archive(fm.getId());
    }

    @Test
    void confirmTagNotFoundDoesNotTriggerArchive() {
        try {
            tagConfirmationService.confirmTag(99999L, 88888L);
        } catch (IllegalArgumentException ignored) {
            // 关联不存在，预期抛异常（事务回滚，不发布事件）
        }
        verify(storageArchiveService, never()).archive(any(Long.class));
    }
}
