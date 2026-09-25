package com.coffer.file.application.archive;

import com.coffer.service.MinioStorageService;
import com.coffer.tag.application.TagConfirmationService;
import com.coffer.file.domain.CategoryType;
import com.coffer.tag.domain.ConfirmationStatus;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.tag.domain.FileTagMapping;
import com.coffer.tag.domain.Tag;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.tag.infrastructure.persistence.TagRepository;
import com.coffer.governance.application.ArchiveObjectNameService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 标签确认 → AFTER_COMMIT 归档 → DB 落库 端到端集成测试（回归守卫）。
 *
 * <p>区别于 {@link TagConfirmationEventTest}（归档服务 Mock，仅验证「触发」关系），本类使用
 * <b>真实</b> {@link StorageArchiveService} + 真实 Repository，验证「确认事务提交后，归档写入
 * 真正落库」——这正是 AFTER_COMMIT 监听器 + REQUIRES_NEW 独立事务的语义（见
 * {@link StorageArchiveService#markArchived} 的注释：监听器执行时确认事务的 EntityManager
 * 仍绑定线程，REQUIRED 的 save 会静默丢写，必须 REQUIRES_NEW）。
 *
 * <p>类级不标注 {@code @Transactional}：否则确认事务不提交、AFTER_COMMIT 不触发，且归档的
 * REQUIRES_NEW 事务看不到外层未提交种子数据。改为每用例前清库。
 */
@SpringBootTest
class StorageArchiveEventIntegrationTest {

    @Autowired
    private TagConfirmationService tagConfirmationService;

    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private FileTagMappingRepository fileTagMappingRepository;

    @MockitoBean
    private MinioStorageService minioStorageService;

    @MockitoBean
    private ArchiveObjectNameService archiveObjectNameService;

    private static final String SOURCE = "files/2025/08/29/pdf/uuid_合同.pdf";
    private static final String TARGET = "contracts/2025/08/29/new-uuid.pdf";

    @BeforeEach
    void cleanDb() {
        // 逻辑外键无 DB 约束，清理顺序自由；全清保证用例互不污染
        fileTagMappingRepository.deleteAll();
        tagRepository.deleteAll();
        fileMetadataRepository.deleteAll();
    }

    private FileMetadata saveFile(CategoryType category) {
        return fileMetadataRepository.save(FileMetadata.builder()
                .fileName("归档合同.pdf").fileSize(10L).fileType("pdf")
                .status(FileStatus.COMPLETED)
                .category(category)
                .archived(false)
                .storagePath(SOURCE)
                .build());
    }

    @Test
    void confirmTagArchivesAndPersistsToDb() {
        FileMetadata fm = saveFile(CategoryType.CONTRACT);
        Tag tag = tagRepository.save(Tag.builder().tagName("归档标签").build());
        fileTagMappingRepository.save(FileTagMapping.builder()
                .fileId(fm.getId()).tagId(tag.getId())
                .confirmationStatus(ConfirmationStatus.PENDING_CONFIRMATION).build());
        when(archiveObjectNameService.generateArchivePath(any(), anyString(), any())).thenReturn(TARGET);

        tagConfirmationService.confirmTag(fm.getId(), tag.getId());

        // 核心回归断言：归档的 DB 更新真正落库（曾因陈旧事务静默丢失而失败）
        FileMetadata after = fileMetadataRepository.findById(fm.getId()).orElseThrow();
        assertThat(after.getStoragePath()).isEqualTo(TARGET);
        assertThat(after.isArchived()).isTrue();
        // 顺序：先复制（旧→新），后删除旧对象；DB 更新夹在中间（InOrder 无法覆盖 DB，但复制必在删除前）
        InOrder inOrder = inOrder(minioStorageService);
        inOrder.verify(minioStorageService).copyObject(SOURCE, TARGET);
        inOrder.verify(minioStorageService).deleteFile(isNull(), eq(SOURCE));
    }

    @Test
    void reConfirmSameTagDoesNotReArchive() {
        FileMetadata fm = saveFile(CategoryType.CONTRACT);
        Tag tag = tagRepository.save(Tag.builder().tagName("归档标签").build());
        fileTagMappingRepository.save(FileTagMapping.builder()
                .fileId(fm.getId()).tagId(tag.getId())
                .confirmationStatus(ConfirmationStatus.PENDING_CONFIRMATION).build());
        when(archiveObjectNameService.generateArchivePath(any(), anyString(), any())).thenReturn(TARGET);

        tagConfirmationService.confirmTag(fm.getId(), tag.getId());
        FileMetadata first = fileMetadataRepository.findById(fm.getId()).orElseThrow();
        assertThat(first.isArchived()).isTrue();

        // 幂等：重复确认已 CONFIRMED 标签，早退不发布事件，不重复移动
        tagConfirmationService.confirmTag(fm.getId(), tag.getId());
        FileMetadata after = fileMetadataRepository.findById(fm.getId()).orElseThrow();
        assertThat(after.getStoragePath()).isEqualTo(TARGET);
        assertThat(after.isArchived()).isTrue();
        verify(minioStorageService).copyObject(SOURCE, TARGET); // 仅一次复制
        verify(minioStorageService).deleteFile(isNull(), eq(SOURCE)); // 仅一次删除
    }
}
