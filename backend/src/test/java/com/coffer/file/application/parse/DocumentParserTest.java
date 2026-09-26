package com.coffer.file.application.parse;

import com.coffer.file.application.parse.DocumentParseService;
import com.coffer.file.domain.parse.ParseResult;
import com.coffer.file.domain.parse.ParseStatus;
import com.coffer.file.infrastructure.parse.PdfParser;
import com.coffer.util.TextTruncator;
import com.coffer.file.infrastructure.parse.TxtParser;
import com.coffer.file.infrastructure.parse.WordParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档解析器单元测试（真实 Spring 上下文，真实解析）。
 */
@SpringBootTest
@ExtendWith(MockitoExtension.class)
class DocumentParserTest extends com.coffer.auth.OwnerTestSupport {

    @Autowired
    private TxtParser txtParser;

    @Autowired
    private PdfParser pdfParser;

    @Autowired
    private WordParser wordParser;

    @Autowired
    private DocumentParseService documentParseService;

    @Autowired
    private TextTruncator textTruncator;

    private InputStream load(String name) {
        InputStream in = getClass().getClassLoader().getResourceAsStream("test-files/" + name);
        assertThat(in).as("测试资源缺失: test-files/%s", name).isNotNull();
        return in;
    }

    @Test
    void testTxtParser() {
        String text = txtParser.parseToString(load("sample.txt"));
        assertThat(text).isNotBlank();
        assertThat(text).contains("AgentFS");
        assertThat(text).contains("关键词");
    }

    @Test
    void testPdfParser() {
        String text = pdfParser.parseToString(load("sample.pdf"));
        assertThat(text).isNotBlank();
        assertThat(text).contains("AgentFS");
        assertThat(text).contains("file-upload");
    }

    @Test
    void testWordParser() {
        // .docx
        String docx = wordParser.parseToString(load("sample.docx"));
        assertThat(docx).isNotBlank();
        assertThat(docx).contains("AgentFS");
        assertThat(docx).contains("文档解析");

        // 旧版 .doc 兼容性（经 Word 另存生成的真实 .doc）
        String doc = wordParser.parseToString(load("sample.doc"));
        assertThat(doc).isNotBlank();
        assertThat(doc).contains("AgentFS");
    }

    @Test
    void testEncryptedPdf() {
        ParseResult result = documentParseService.extractTextFromFile("encrypted.pdf", load("encrypted.pdf"));
        assertThat(result.getStatus()).isEqualTo(ParseStatus.ENCRYPTED);
        assertThat(result.getErrorMessage()).contains("加密");
    }

    @Test
    void testCorruptedWord() {
        ParseResult result = documentParseService.extractTextFromFile("corrupted.docx", load("corrupted.docx"));
        assertThat(result.getStatus()).isEqualTo(ParseStatus.CORRUPTED);
    }

    @Test
    void testTextTruncator() {
        String longText = "A".repeat(20000);
        TextTruncator.TruncationStats stats = textTruncator.truncateWithStats(longText);
        assertThat(stats.truncated()).isTrue();
        assertThat(stats.originalCharCount()).isEqualTo(20000);
        assertThat(stats.text()).startsWith("A".repeat(10000));
        assertThat(stats.text()).endsWith("...（内容过长已截断）");

        TextTruncator.TruncationStats shortStats = textTruncator.truncateWithStats("short");
        assertThat(shortStats.truncated()).isFalse();
        assertThat(shortStats.text()).isEqualTo("short");
    }

    @Test
    void testFallbackEncoding() {
        String gbk = "中文乱码兜底测试，GBK 编码内容";
        byte[] gbkBytes = gbk.getBytes(Charset.forName("GBK"));
        ParseResult result = documentParseService
                .extractTextWithFallback("fallback.txt", new ByteArrayInputStream(gbkBytes));
        assertThat(result.getStatus()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(result.getContent()).contains("中文乱码兜底测试");
    }
}
