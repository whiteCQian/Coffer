package com.coffer.file.application.archive;

import com.coffer.service.MinioStorageService;
import com.coffer.file.domain.CategoryType;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 分类目录归档服务集成测试：copy → DB 更新 → delete 三步顺序、幂等（已归档跳过）、
 * copy 失败不破坏原文件、delete 失败容忍冗余、OTHER 兜底、文件缺失跳过。
 *
 * <p><b>本类不标注 {@code @Transactional}</b>：归档的 DB 更新走 {@code REQUIRES_NEW}
 * 独立事务（见 {@link StorageArchiveService#markArchived}），若测试用外层事务包裹归档，
 * 独立事务将看不到外层未提交的种子数据，无法真实验证落库。故改为非事务 + 每用例前清库。
 *
 * <p>{@link MinioStorageService} 与 {@link ArchiveObjectNameService} 以 Mock 替换（外部 IO / 路径计算），
 * {@link FileMetadataRepository} 与 {@link StorageArchiveService} 用真实 Bean。
 */
@SpringBootTest
class StorageArchiveServiceTest {

    @Autowired
    private StorageArchiveService storageArchiveService;

    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    @MockitoBean
    private ArchiveObjectNameService archiveObjectNameService;

    @MockitoBean
    private MinioStorageService minioStorageService;

    private static final String SOURCE = "files/2025/08/29/pdf/uuid_合同.pdf";
    private static final String TARGET = "contracts/2025/08/29/new-uuid.pdf";

    @BeforeEach
    void cleanDb() {
        fileMetadataRepository.deleteAll();
    }

    private FileMetadata saveFile(CategoryType category, boolean archived) {
        return fileMetadataRepository.save(FileMetadata.builder()
                .fileName("合同.pdf").fileSize(10L).fileType("pdf")
                .status(FileStatus.COMPLETED)
                .category(category)
                .archived(archived)
                .storagePath(SOURCE)
                .build());
    }

    @Test
    void archiveCopiesMovesAndDeletesOldObject() {
        FileMetadata fm = saveFile(CategoryType.CONTRACT, false);
        when(archiveObjectNameService.generateArchivePath(any(), anyString(), any())).thenReturn(TARGET);

        storageArchiveService.archive(fm.getId());

        FileMetadata saved = fileMetadataRepository.findById(fm.getId()).orElseThrow();
        assertThat(saved.getStoragePath()).isEqualTo(TARGET);
        assertThat(saved.isArchived()).isTrue();
        // 三步顺序：先复制（旧→新），后删除旧对象
        InOrder inOrder = inOrder(minioStorageService);
        inOrder.verify(minioStorageService).copyObject(SOURCE, TARGET);
        inOrder.verify(minioStorageService).deleteFile(isNull(), eq(SOURCE));
    }

    @Test
    void copyFailureLeavesFileUntouched() {
        FileMetadata fm = saveFile(CategoryType.CONTRACT, false);
        when(archiveObjectNameService.generateArchivePath(any(), anyString(), any())).thenReturn(TARGET);
        doThrow(new RuntimeException("copy fail"))
                .when(minioStorageService).copyObject(anyString(), anyString());

        storageArchiveService.archive(fm.getId()); // 归档吞异常，不抛出

        FileMetadata saved = fileMetadataRepository.findById(fm.getId()).orElseThrow();
        assertThat(saved.getStoragePath()).isEqualTo(SOURCE); // 文件仍在旧路径，无损失
        assertThat(saved.isArchived()).isFalse();
        verify(minioStorageService, never()).deleteFile(isNull(), anyString());
    }

    @Test
    void alreadyArchivedSkipsMove() {
        FileMetadata fm = saveFile(CategoryType.CONTRACT, true);

        storageArchiveService.archive(fm.getId());

        FileMetadata saved = fileMetadataRepository.findById(fm.getId()).orElseThrow();
        assertThat(saved.getStoragePath()).isEqualTo(SOURCE);
        assertThat(saved.isArchived()).isTrue();
        verifyNoInteractions(minioStorageService);
    }

    @Test
    void otherCategoryArchivesToUncategorized() {
        FileMetadata fm = saveFile(CategoryType.OTHER, false);
        String uncategorizedTarget = "uncategorized/2025/08/29/uuid.pdf";
        when(archiveObjectNameService.generateArchivePath(any(), anyString(), any())).thenReturn(uncategorizedTarget);

        storageArchiveService.archive(fm.getId());

        FileMetadata saved = fileMetadataRepository.findById(fm.getId()).orElseThrow();
        assertThat(saved.getStoragePath()).startsWith("uncategorized/");
        assertThat(saved.isArchived()).isTrue();
        verify(minioStorageService).copyObject(SOURCE, uncategorizedTarget);
    }

    @Test
    void deleteFailureStillMarksArchived() {
        FileMetadata fm = saveFile(CategoryType.CONTRACT, false);
        when(archiveObjectNameService.generateArchivePath(any(), anyString(), any())).thenReturn(TARGET);
        doThrow(new RuntimeException("delete fail"))
                .when(minioStorageService).deleteFile(isNull(), anyString());

        storageArchiveService.archive(fm.getId()); // delete 失败仅冗余对象，不影响正确性

        FileMetadata saved = fileMetadataRepository.findById(fm.getId()).orElseThrow();
        assertThat(saved.getStoragePath()).isEqualTo(TARGET);
        assertThat(saved.isArchived()).isTrue();
    }

    @Test
    void missingFileIsSkipped() {
        storageArchiveService.archive(999999L);

        verifyNoInteractions(minioStorageService);
    }
}
