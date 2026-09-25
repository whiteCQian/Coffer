package com.coffer.file.application;

import com.coffer.service.MinioStorageService;
import com.coffer.file.api.dto.CategoryCountResponse;
import com.coffer.file.api.dto.FileDetailResponse;
import com.coffer.file.api.dto.FileListResponse;
import com.coffer.tag.api.dto.FileTagInfo;
import com.coffer.file.domain.CategoryType;
import com.coffer.tag.domain.ConfirmationStatus;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.tag.domain.FileTagMapping;
import com.coffer.tag.domain.Tag;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.tag.infrastructure.persistence.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

/**
 * 文件列表查询集成测试：验证 tagStatus 汇总规则（NO_TAG/PENDING/ALL_CONFIRMED/ALL_REJECTED/混合）、
 * 关键词搜索（文件名/标签）与分页。
 *
 * <p>{@link Transactional} 保证每个用例的种子数据自动回滚，互不污染。
 */
@SpringBootTest
@Transactional
class FileServiceTest {

    @Autowired
    private FileService fileService;

    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    @Autowired
    private FileTagMappingRepository fileTagMappingRepository;

    @Autowired
    private TagRepository tagRepository;

    /** 预览 URL 由 MinIO 预签名生成，测试中 Mock，避免单测依赖真实 MinIO 服务。 */
    @MockitoBean
    private MinioStorageService minioStorageService;

    @BeforeEach
    void isolateCommittedDataFromNonTransactionalIntegrationTests() {
        fileTagMappingRepository.deleteAll();
        tagRepository.deleteAll();
        fileMetadataRepository.deleteAll();
    }

    private FileMetadata saveFile(String fileName) {
        return fileMetadataRepository.save(FileMetadata.builder()
                .fileName(fileName).fileSize(10L).fileType("txt")
                .status(com.coffer.file.domain.FileStatus.COMPLETED).build());
    }

    private FileMetadata saveFile(String fileName, CategoryType category) {
        return fileMetadataRepository.save(FileMetadata.builder()
                .fileName(fileName).fileSize(10L).fileType("txt")
                .status(com.coffer.file.domain.FileStatus.COMPLETED)
                .category(category).build());
    }

    private Long saveTag(String tagName) {
        return tagRepository.save(Tag.builder().tagName(tagName).build()).getId();
    }

    private void saveMapping(Long fileId, Long tagId, ConfirmationStatus status) {
        fileTagMappingRepository.save(FileTagMapping.builder()
                .fileId(fileId).tagId(tagId).confirmationStatus(status)
                .confirmedAt(status == ConfirmationStatus.PENDING_CONFIRMATION ? null : LocalDateTime.now())
                .build());
    }

    private FileListResponse findById(Page<FileListResponse> page, Long id) {
        return page.getContent().stream().filter(r -> r.getId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void tagStatusNoTag() {
        FileMetadata fm = saveFile("无标签文档.txt");

        FileListResponse r = findById(fileService.listFiles(null, PageRequest.of(0, 10)), fm.getId());

        assertThat(r.getTagStatus()).isEqualTo("NO_TAG");
    }

    @Test
    void tagStatusPendingHasHighestPriority() {
        FileMetadata fm = saveFile("待确认文档.txt");
        saveMapping(fm.getId(), saveTag("标签A"), ConfirmationStatus.CONFIRMED);
        saveMapping(fm.getId(), saveTag("标签B"), ConfirmationStatus.PENDING_CONFIRMATION);
        saveMapping(fm.getId(), saveTag("标签C"), ConfirmationStatus.REJECTED);

        FileListResponse r = findById(fileService.listFiles(null, PageRequest.of(0, 10)), fm.getId());

        assertThat(r.getTagStatus()).isEqualTo("PENDING");
    }

    @Test
    void tagStatusAllConfirmed() {
        FileMetadata fm = saveFile("已确认文档.txt");
        saveMapping(fm.getId(), saveTag("标签D"), ConfirmationStatus.CONFIRMED);
        saveMapping(fm.getId(), saveTag("标签E"), ConfirmationStatus.CONFIRMED);

        FileListResponse r = findById(fileService.listFiles(null, PageRequest.of(0, 10)), fm.getId());

        assertThat(r.getTagStatus()).isEqualTo("ALL_CONFIRMED");
    }

    @Test
    void tagStatusAllRejected() {
        FileMetadata fm = saveFile("全拒绝文档.txt");
        saveMapping(fm.getId(), saveTag("标签F"), ConfirmationStatus.REJECTED);

        FileListResponse r = findById(fileService.listFiles(null, PageRequest.of(0, 10)), fm.getId());

        assertThat(r.getTagStatus()).isEqualTo("ALL_REJECTED");
    }

    @Test
    void tagStatusMixedConfirmedAndRejectedSimplifiedToPending() {
        FileMetadata fm = saveFile("混合文档.txt");
        saveMapping(fm.getId(), saveTag("标签G"), ConfirmationStatus.CONFIRMED);
        saveMapping(fm.getId(), saveTag("标签H"), ConfirmationStatus.REJECTED);

        FileListResponse r = findById(fileService.listFiles(null, PageRequest.of(0, 10)), fm.getId());

        assertThat(r.getTagStatus()).isEqualTo("PENDING");
    }

    @Test
    void searchByFileNameKeyword() {
        saveFile("合同扫描件.pdf");
        saveFile("无关文件.txt");

        Page<FileListResponse> page = fileService.listFiles("合同", PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getFileName()).isEqualTo("合同扫描件.pdf");
    }

    @Test
    void searchByTagNameKeyword() {
        FileMetadata fm = saveFile("文件名不匹配.txt");
        Long tagId = saveTag("财务报表");
        saveMapping(fm.getId(), tagId, ConfirmationStatus.CONFIRMED);

        Page<FileListResponse> page = fileService.listFiles("财务", PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getId()).isEqualTo(fm.getId());
    }

    @Test
    void searchByTagNameKeywordIncludesPendingExcludesRejected() {
        // 列表关键词搜索匹配「未拒绝」标签（含已确认/待确认，与 repository 各搜索查询及前端契约一致），
        // 已拒绝标签不命中——保证 AI 刚打完标、尚未人工确认的文件也能被关键词检索到，便于用户去确认。
        FileMetadata pendingFile = saveFile("待打标文件A.txt");
        saveMapping(pendingFile.getId(), saveTag("待审标签A"), ConfirmationStatus.PENDING_CONFIRMATION);
        FileMetadata rejectedFile = saveFile("待打标文件B.txt");
        saveMapping(rejectedFile.getId(), saveTag("待审标签B"), ConfirmationStatus.REJECTED);

        Page<FileListResponse> page = fileService.listFiles("待审", PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getId()).isEqualTo(pendingFile.getId());
    }

    @Test
    void searchFilesTagMatchesNonRejectedTags() {
        // tag 维度匹配「未拒绝」标签（已确认+待确认，与前端契约一致）：同一标签映射到多个文件时，
        // 待确认的也应命中；已拒绝映射的文件不命中。
        Long tagId = saveTag("独家标签甲");
        FileMetadata confirmedFile = saveFile("已确认文件.txt");
        saveMapping(confirmedFile.getId(), tagId, ConfirmationStatus.CONFIRMED);
        FileMetadata pendingFile = saveFile("待确认文件.txt");
        saveMapping(pendingFile.getId(), tagId, ConfirmationStatus.PENDING_CONFIRMATION);
        FileMetadata rejectedFile = saveFile("已拒绝文件.txt");
        saveMapping(rejectedFile.getId(), tagId, ConfirmationStatus.REJECTED);

        Page<FileListResponse> page = fileService.searchFiles(null, "独家标签甲", PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent())
                .extracting(FileListResponse::getId)
                .containsExactlyInAnyOrder(confirmedFile.getId(), pendingFile.getId());
    }

    @Test
    void searchFilesKeywordAndTagIntersection() {
        // keyword 匹配文件名，tag 匹配已确认标签，两者取交集
        FileMetadata fm = saveFile("年度合同汇总.pdf");
        saveMapping(fm.getId(), saveTag("财务"), ConfirmationStatus.CONFIRMED);
        FileMetadata other = saveFile("年度合同汇总.pdf");
        saveMapping(other.getId(), saveTag("人事"), ConfirmationStatus.CONFIRMED);

        Page<FileListResponse> page = fileService.searchFiles("合同", "财务", PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getId()).isEqualTo(fm.getId());
    }

    @Test
    void searchFilesBlankBothReturnsAll() {
        FileMetadata fm = saveFile("全量搜索文档.txt");
        saveMapping(fm.getId(), saveTag("标签Z"), ConfirmationStatus.CONFIRMED);

        Page<FileListResponse> page = fileService.searchFiles(null, null, PageRequest.of(0, 100));

        assertThat(page.getContent())
                .anyMatch(r -> r.getId().equals(fm.getId()));
    }

    @Test
    void paginationByKeyword() {
        saveFile("Paging_甲.txt");
        saveFile("Paging_乙.txt");
        saveFile("Paging_丙.txt");

        Page<FileListResponse> page1 = fileService.listFiles("Paging", PageRequest.of(0, 2));
        assertThat(page1.getTotalElements()).isEqualTo(3);
        assertThat(page1.getTotalPages()).isEqualTo(2);
        assertThat(page1.getContent()).hasSize(2);

        Page<FileListResponse> page2 = fileService.listFiles("Paging", PageRequest.of(1, 2));
        assertThat(page2.getContent()).hasSize(1);
    }

    @Test
    void blankKeywordReturnsAll() {
        FileMetadata fm = saveFile("全量列表文档.txt");

        Page<FileListResponse> page = fileService.listFiles("   ", PageRequest.of(0, 100));

        assertThat(page.getContent())
                .anyMatch(r -> r.getId().equals(fm.getId()));
    }

    @Test
    void listFilesFiltersByCategoryOnly() {
        saveFile("年度合同.pdf", CategoryType.CONTRACT);
        saveFile("报销发票.pdf", CategoryType.INVOICE);

        Page<FileListResponse> page = fileService.listFiles(null, "CONTRACT", null, null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getFileName()).isEqualTo("年度合同.pdf");
    }

    @Test
    void listFilesKeywordAndCategoryIntersect() {
        // 关键词与分类取交集：文件名命中但分类不符的文件不返回
        saveFile("年度合同.pdf", CategoryType.CONTRACT);
        saveFile("试用合同.pdf", CategoryType.OTHER);
        saveFile("无关文件.pdf", CategoryType.CONTRACT);

        Page<FileListResponse> page = fileService.listFiles("合同", "CONTRACT", null, null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getFileName()).isEqualTo("年度合同.pdf");
    }

    @Test
    void listFilesKeywordMatchesSummary() {
        // 文件名不含关键词，但 AI 摘要命中 → 摘要参与关键词匹配
        FileMetadata fm = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("扫描件_乱码_0231.pdf").fileSize(10L).fileType("pdf")
                .status(FileStatus.COMPLETED)
                .summary("本文件为 2025 年第一季度财务对账单汇总。")
                .build());

        Page<FileListResponse> page = fileService.listFiles("对账单", null, null, null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getId()).isEqualTo(fm.getId());
    }

    @Test
    void listFilesSortNameAsc() {
        saveFile("B_file.txt");
        saveFile("A_file.txt");

        Page<FileListResponse> page = fileService.listFiles(null, null, null, "name", PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(FileListResponse::getFileName)
                .containsExactly("A_file.txt", "B_file.txt");
    }

    @Test
    void listFilesSortSizeDesc() {
        // saveFile 辅助方法固定 size=10，此处构造不同大小文件验证 size 排序
        fileMetadataRepository.save(FileMetadata.builder().fileName("小文件.txt")
                .fileSize(100L).fileType("txt").status(FileStatus.COMPLETED).build());
        fileMetadataRepository.save(FileMetadata.builder().fileName("大文件.txt")
                .fileSize(10000L).fileType("txt").status(FileStatus.COMPLETED).build());

        Page<FileListResponse> page = fileService.listFiles(null, null, null, "size", PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(FileListResponse::getFileName)
                .containsExactly("大文件.txt", "小文件.txt");
    }

    @Test
    void listFilesInvalidSortTokenThrows() {
        assertThatThrownBy(() -> fileService.listFiles(null, null, null, "bogus", PageRequest.of(0, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法排序参数");
    }

    @Test
    void listFilesInvalidCategoryThrows() {
        assertThatThrownBy(() -> fileService.listFiles(null, "NOPE", null, null, PageRequest.of(0, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法分类参数");
    }

    @Test
    void listFilesBlankCategoryAndSortFallbackNoThrow() {
        // 空白分类/排序按"不约束 + 默认 new"处理，等价于全量列表
        saveFile("任意文件.txt");

        Page<FileListResponse> page = fileService.listFiles(null, "  ", null, "  ", PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void getFileDetailReturnsConfirmedAndPendingTags() {
        FileMetadata fm = saveFile("详情文档.pdf");
        Long tagId1 = saveTag("标签A");
        Long tagId2 = saveTag("标签B");
        Long tagId3 = saveTag("标签C");
        saveMapping(fm.getId(), tagId1, ConfirmationStatus.CONFIRMED);
        saveMapping(fm.getId(), tagId2, ConfirmationStatus.PENDING_CONFIRMATION);
        saveMapping(fm.getId(), tagId3, ConfirmationStatus.REJECTED);

        when(minioStorageService.generatePresignedUrl(nullable(String.class), nullable(String.class),
                nullable(Duration.class))).thenReturn("http://preview/url");

        FileDetailResponse detail = fileService.getFileDetail(fm.getId());

        assertThat(detail.getFileName()).isEqualTo("详情文档.pdf");
        assertThat(detail.getStatus()).isEqualTo("COMPLETED");
        assertThat(detail.getTagStatus()).isEqualTo("PENDING"); // 存在待确认
        assertThat(detail.getPreviewUrl()).isEqualTo("http://preview/url");
        // 标签按确认状态分组，已拒绝的不返回
        assertThat(detail.getConfirmedTags()).hasSize(1);
        assertThat(detail.getConfirmedTags().get(0).getTagName()).isEqualTo("标签A");
        assertThat(detail.getPendingTags()).hasSize(1);
        assertThat(detail.getPendingTags().get(0).getTagName()).isEqualTo("标签B");
    }

    @Test
    void getFileDetailNotFoundThrows() {
        assertThatThrownBy(() -> fileService.getFileDetail(999999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件不存在");
    }

    @Test
    void getFileDetailExposesCategoryArchivedAndStoragePath() {
        FileMetadata fm = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("归档合同.pdf").fileSize(10L).fileType("pdf")
                .status(FileStatus.COMPLETED)
                .category(CategoryType.CONTRACT)
                .archived(true)
                .storagePath("contracts/2025/08/29/uuid.pdf")
                .build());
        when(minioStorageService.generatePresignedUrl(nullable(String.class), nullable(String.class),
                nullable(Duration.class))).thenReturn("http://preview/url");

        FileDetailResponse detail = fileService.getFileDetail(fm.getId());

        assertThat(detail.getCategory()).isEqualTo("CONTRACT");
        assertThat(detail.getArchived()).isTrue();
        assertThat(detail.getStoragePath()).isEqualTo("contracts/2025/08/29/uuid.pdf");
    }

    @Test
    void listCategoriesReturnsCountsDescAndSkipsEmptyCategories() {
        saveFile("合同一.pdf", CategoryType.CONTRACT);
        saveFile("合同二.pdf", CategoryType.CONTRACT);
        saveFile("发票一.pdf", CategoryType.INVOICE);

        List<CategoryCountResponse> data = fileService.listCategories().getData();

        // 仅含 count>0 的分类，按数量倒序；无文件的分类（REPORT 等）不出现
        assertThat(data)
                .extracting(CategoryCountResponse::getCategory, CategoryCountResponse::getCount)
                .containsExactly(
                        tuple("CONTRACT", 2L),
                        tuple("INVOICE", 1L));
    }

    @Test
    void listCategoriesMapsEnumNameAndCount() {
        saveFile("合同.pdf", CategoryType.CONTRACT);
        saveFile("其他.txt", CategoryType.OTHER);

        List<CategoryCountResponse> data = fileService.listCategories().getData();

        assertThat(data)
                .extracting(CategoryCountResponse::getCategory)
                .containsExactlyInAnyOrder("CONTRACT", "OTHER");
        assertThat(data).allSatisfy(c -> assertThat(c.getCount()).isEqualTo(1L));
    }

    @Test
    void listCategoriesEmptyRepoReturnsEmptyList() {
        List<CategoryCountResponse> data = fileService.listCategories().getData();

        assertThat(data).isEmpty();
    }

    @Test
    void listFilesEnrichesCategoryStatusArchivedAndNonRejectedTags() {
        FileMetadata fm = saveFile("合同.pdf", CategoryType.CONTRACT);
        Long confirmed = saveTag("合同");
        Long pending = saveTag("发票");
        Long rejected = saveTag("作废");
        saveMapping(fm.getId(), confirmed, ConfirmationStatus.CONFIRMED);
        saveMapping(fm.getId(), pending, ConfirmationStatus.PENDING_CONFIRMATION);
        saveMapping(fm.getId(), rejected, ConfirmationStatus.REJECTED);
        FileMetadata archived = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("已归档.pdf").fileSize(10L).fileType("pdf")
                .status(FileStatus.COMPLETED).category(CategoryType.REPORT).archived(true).build());

        FileListResponse r = findById(fileService.listFiles(null, PageRequest.of(0, 10)), fm.getId());
        FileListResponse ar = findById(fileService.listFiles(null, PageRequest.of(0, 10)), archived.getId());

        // 元数据字段如实回填
        assertThat(r.getCategory()).isEqualTo("CONTRACT");
        assertThat(r.getStatus()).isEqualTo("COMPLETED");
        assertThat(r.isArchived()).isFalse();
        assertThat(ar.getCategory()).isEqualTo("REPORT");
        assertThat(ar.isArchived()).isTrue();
        // 标签明细：非拒绝（CONFIRMED + PENDING_CONFIRMATION）都在，REJECTED 被排除
        assertThat(r.getTags())
                .extracting(FileTagInfo::getTagName)
                .containsExactlyInAnyOrder("合同", "发票");
        assertThat(r.getTags()).allSatisfy(t -> assertThat(t.getConfirmationStatus())
                .isIn("CONFIRMED", "PENDING_CONFIRMATION"));
        // 无标签文件 → 空数组而非 null
        assertThat(ar.getTags()).isNotNull().isEmpty();
    }
}
