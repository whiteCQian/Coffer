package com.coffer.file.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 受控分类词表单元测试：中文 label 匹配、大小写/空白容错、未命中兜底 OTHER、slug 映射闭合。
 */
class CategoryTypeTest {

    @Test
    void fromLabelMatchesChineseLabel() {
        assertThat(CategoryType.fromLabel("合同")).isEqualTo(CategoryType.CONTRACT);
        assertThat(CategoryType.fromLabel("发票")).isEqualTo(CategoryType.INVOICE);
        assertThat(CategoryType.fromLabel("报告")).isEqualTo(CategoryType.REPORT);
        assertThat(CategoryType.fromLabel("证件")).isEqualTo(CategoryType.ID);
        assertThat(CategoryType.fromLabel("图片")).isEqualTo(CategoryType.IMAGE);
        assertThat(CategoryType.fromLabel("视频")).isEqualTo(CategoryType.VIDEO);
        assertThat(CategoryType.fromLabel("其他")).isEqualTo(CategoryType.OTHER);
    }

    @Test
    void fromLabelTrimsAndIgnoresCase() {
        assertThat(CategoryType.fromLabel("  发票  ")).isEqualTo(CategoryType.INVOICE);
        assertThat(CategoryType.fromLabel("contract")).isEqualTo(CategoryType.CONTRACT);
        assertThat(CategoryType.fromLabel("CONTRACT")).isEqualTo(CategoryType.CONTRACT);
    }

    @Test
    void fromLabelUnknownFallsBackToOther() {
        assertThat(CategoryType.fromLabel("乱写")).isEqualTo(CategoryType.OTHER);
        assertThat(CategoryType.fromLabel("")).isEqualTo(CategoryType.OTHER);
        assertThat(CategoryType.fromLabel("   ")).isEqualTo(CategoryType.OTHER);
        assertThat(CategoryType.fromLabel(null)).isEqualTo(CategoryType.OTHER);
    }

    @Test
    void slugMappingClosed() {
        assertThat(CategoryType.CONTRACT.getSlug()).isEqualTo("contracts");
        assertThat(CategoryType.INVOICE.getSlug()).isEqualTo("invoices");
        assertThat(CategoryType.REPORT.getSlug()).isEqualTo("reports");
        assertThat(CategoryType.ID.getSlug()).isEqualTo("ids");
        assertThat(CategoryType.IMAGE.getSlug()).isEqualTo("images");
        assertThat(CategoryType.VIDEO.getSlug()).isEqualTo("videos");
        assertThat(CategoryType.OTHER.getSlug()).isEqualTo("uncategorized");
    }

    @Test
    void labelsAreUnique() {
        long distinctLabels = Arrays.stream(CategoryType.values())
                .map(CategoryType::getLabel)
                .distinct()
                .count();
        assertThat(distinctLabels).isEqualTo(CategoryType.values().length);
    }
}
