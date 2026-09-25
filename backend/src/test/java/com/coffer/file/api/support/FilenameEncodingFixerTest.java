package com.coffer.file.api.support;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上传文件名编码还原测试。
 *
 * <p>锁定三个关键行为：GBK 客户端乱码可还原、UTF-8 中文名不被误伤、ASCII 与纯 Latin-1 名不变。
 */
class FilenameEncodingFixerTest {

    /**
     * 模拟 Tomcat 收到 GBK 字节后的产物：把「冒烟合同.txt」的 GBK 字节按 ISO-8859-1 解码。
     */
    private static String gbkMojibake(String chineseName) {
        byte[] gbk = chineseName.getBytes(Charset.forName("GBK"));
        return new String(gbk, StandardCharsets.ISO_8859_1);
    }

    @Test
    void recoversGbkMojibake() {
        String recovered = FilenameEncodingFixer.fix(gbkMojibake("冒烟合同.txt"));
        assertThat(recovered).isEqualTo("冒烟合同.txt");
    }

    @Test
    void keepsCorrectUtf8ChineseUnchanged() {
        // UTF-8 客户端正常还原后的中文名（含 >0xFF 字符）必须原样保留，不能二次转码损坏
        assertThat(FilenameEncodingFixer.fix("冒烟合同.txt")).isEqualTo("冒烟合同.txt");
        assertThat(FilenameEncodingFixer.fix("销售报告-2026季度.txt")).isEqualTo("销售报告-2026季度.txt");
    }

    @Test
    void keepsAsciiUnchanged() {
        assertThat(FilenameEncodingFixer.fix("sales-report.txt")).isEqualTo("sales-report.txt");
        assertThat(FilenameEncodingFixer.fix("a b.c")).isEqualTo("a b.c");
    }

    @Test
    void keepsPureLatin1Unchanged() {
        // é (U+00E9) 为单字节：按 UTF-8/GBK 解释均非法，应保持原值而非破坏
        assertThat(FilenameEncodingFixer.fix("café.txt")).isEqualTo("café.txt");
    }

    @Test
    void nullAndBlankReturnAsIs() {
        assertThat(FilenameEncodingFixer.fix(null)).isNull();
        assertThat(FilenameEncodingFixer.fix("")).isEmpty();
        assertThat(FilenameEncodingFixer.fix("  ")).isEqualTo("  ");
    }
}
