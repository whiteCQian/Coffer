package com.coffer.file.infrastructure.storage;

import com.coffer.file.domain.CategoryType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 归档路径生成单元测试：分类 slug + 上传时间分层目录 + 三位十六进制序号 + 文件名安全化。
 */
class PathGeneratorTest {
    @org.junit.jupiter.api.BeforeEach void owner() { com.coffer.auth.service.TenantContext.set(7L); }
    @org.junit.jupiter.api.AfterEach void clear() { com.coffer.auth.service.TenantContext.clear(); }

    private final PathGenerator pathGenerator = new PathGenerator();

    @Test
    void generateArchivePathUsesCategorySlugDateAndSequence() {
        String path = pathGenerator.generateArchivePath(
                CategoryType.CONTRACT, "销售合同.PDF", LocalDateTime.of(2025, 8, 29, 10, 30));

        assertThat(path).isEqualTo("users/7/archive/contracts/2025/08/29/000-销售合同.pdf");
    }

    @Test
    void nullCategoryArchivesToUncategorized() {
        String path = pathGenerator.generateArchivePath(null, "乱文件.xyz", LocalDateTime.of(2025, 1, 1, 0, 0));

        assertThat(path).startsWith("users/7/archive/uncategorized/2025/01/01/");
    }

    @Test
    void noExtensionKeepsSanitizedNameAfterSequence() {
        String path = pathGenerator.generateArchivePath(
                CategoryType.REPORT, "无扩展名", LocalDateTime.of(2025, 6, 15, 12, 0));

        String fileName = path.substring("users/7/archive/reports/2025/06/15/".length());
        assertThat(fileName).isEqualTo("000-无扩展名");
        assertThat(fileName).doesNotContain(".");
    }

    @Test
    void nullUploadTimeUsesNow() {
        String path = pathGenerator.generateArchivePath(CategoryType.INVOICE, "发票.pdf", null);

        assertThat(path).startsWith("users/7/archive/invoices/");
        assertThat(path).endsWith(".pdf"); // 扩展名统一小写
    }

    @Test
    void illegalCharsInExtensionSanitized() {
        // 最后点后的 "p n g" 仍会清洗为空白下划线
        String path = pathGenerator.generateArchivePath(
                CategoryType.IMAGE, "图?片.p n g", LocalDateTime.of(2025, 5, 5, 5, 5));

        String fileName = path.substring("users/7/archive/images/2025/05/05/".length());
        assertThat(fileName).isEqualTo("000-图_片.p_n_g");
    }

    @Test
    void sequenceUsesThreeUppercaseHexDigits() {
        String path = pathGenerator.generateArchivePath(
                CategoryType.CONTRACT, "采购合同.TXT", LocalDateTime.of(2026, 9, 21, 10, 30), 0xAF);

        assertThat(path).isEqualTo("users/7/archive/contracts/2026/09/21/0AF-采购合同.txt");
    }
}
