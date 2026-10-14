package com.coffer.util;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ArchiveFileNameTest {
    @Test void keepsBusinessNamesAndAppendsAnOrderedFourCharacterCounter() {
        assertThat(ArchiveFileName.numbered("采购合同.pdf", 0)).isEqualTo("采购合同-0001.pdf");
        assertThat(ArchiveFileName.numbered("采购合同.pdf", 1)).isEqualTo("采购合同-0002.pdf");
        assertThat(ArchiveFileName.numbered("采购合同.pdf", 8)).isEqualTo("采购合同-0009.pdf");
        assertThat(ArchiveFileName.numbered("采购合同.pdf", 9)).isEqualTo("采购合同-000A.pdf");
        assertThat(ArchiveFileName.numbered("采购合同.pdf", ArchiveFileName.MAX_SEQUENCE_NUMBER)).isEqualTo("采购合同-ZZZZ.pdf");
        assertThatThrownBy(() -> ArchiveFileName.numbered("采购合同.pdf", ArchiveFileName.MAX_SEQUENCE_NUMBER + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArchiveFileName.numbered("采购合同.pdf", -1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void preservesLongBusinessNumbersDatesAndEnglishProductNames() {
        for (String name : List.of("订单123456789012.pdf", "Qwen3使用说明.txt", "采购合同-2026.pdf", "AB1234CD5678-合同.docx")) {
            int dot = name.lastIndexOf('.');
            assertThat(ArchiveFileName.numbered(name, 0)).isEqualTo(name.substring(0, dot) + "-0001" + name.substring(dot));
        }
    }
    @Test void replacesOnlyTheRecognizableLegacyRandomUuidSuffix() {
        assertThat(ArchiveFileName.numbered("server-smoke-d0834cf3-0d6a-410b-9c9b-8b7ca7a6b44d.txt", 0))
                .isEqualTo("server-smoke-0001.txt");
        assertThat(ArchiveFileName.numbered("订单-123456789.txt", 0)).isEqualTo("订单-123456789-0001.txt");
    }
    @Test void stripsAnOwnedArchiveCounterOnReanalysisWithoutTreatingABusinessYearAsACounter() {
        String path = "users/7/archive/contracts/2026/10/13/采购合同-2026-0001.pdf";
        assertThat(ArchiveFileName.sourceName("采购合同-2026-0001.pdf", path, true)).isEqualTo("采购合同-2026.pdf");
        assertThat(ArchiveFileName.sourceName("采购合同-2026.pdf", "users/7/archive/contracts/2026/10/13/000-采购合同-2026.pdf", true))
                .isEqualTo("采购合同-2026.pdf");
        assertThat(ArchiveFileName.sourceName("采购合同-2026.pdf", "users/7/files/采购合同-2026.pdf", false))
                .isEqualTo("采购合同-2026.pdf");
        assertThat(ArchiveFileName.numbered(ArchiveFileName.sourceName("采购合同-2026-0001.pdf", path, true), 1))
                .isEqualTo("采购合同-2026-0002.pdf");
    }
    @Test void worksWithNoExtensionAndCompoundExtensions() {
        assertThat(ArchiveFileName.numbered("项目资料", 0)).isEqualTo("项目资料-0001");
        assertThat(ArchiveFileName.numbered("项目资料.tar.gz", 0)).isEqualTo("项目资料.tar-0001.gz");
    }
}
