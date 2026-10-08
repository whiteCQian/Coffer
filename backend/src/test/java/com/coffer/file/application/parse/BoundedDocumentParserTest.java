package com.coffer.file.application.parse;

import com.coffer.file.domain.parse.ParseStatus;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.assertj.core.api.Assertions.assertThat;

class BoundedDocumentParserTest {
    private final BoundedDocumentParser parser = new BoundedDocumentParser();

    @Test void txtPositionsAreSourceLinesAndCharacters() {
        var result = parser.parse(7L, 3L, "笔记.txt",
                new ByteArrayInputStream("第一行\nsecond".getBytes(StandardCharsets.UTF_8)));
        assertThat(result.status()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(result.fileId()).isEqualTo(7L);
        assertThat(result.revision()).isEqualTo(3L);
        assertThat(result.chunks()).extracting(c -> c.sourceKind() + ":" + c.start())
                .containsExactly("LINE:1", "LINE:2");
        assertThat(result.chunks().get(0).startCharacter()).isEqualTo(1);
    }

    @Test void pdfAndWordCarryRealLocations() {
        var pdf = parser.parse(7L, 3L, "sample.pdf", resource("sample.pdf"));
        assertThat(pdf.status()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(pdf.chunks()).allMatch(c -> c.sourceKind().equals("PAGE") && c.start() > 0);
        var word = parser.parse(7L, 3L, "sample.docx", resource("sample.docx"));
        assertThat(word.status()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(word.chunks()).allMatch(c -> c.sourceKind().equals("PARAGRAPH") && c.start() > 0);
    }

    @Test void mismatchedAndEncryptedFilesNeverProduceGuessedText() {
        var wrong = parser.parse(1L, 0, "fake.pdf", new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        assertThat(wrong.status()).isEqualTo(ParseStatus.TYPE_MISMATCH);
        assertThat(wrong.chunks()).isEmpty();
        var encrypted = parser.parse(1L, 0, "encrypted.pdf", resource("encrypted.pdf"));
        assertThat(encrypted.status()).isEqualTo(ParseStatus.ENCRYPTED);
        assertThat(encrypted.chunks()).isEmpty();
    }

    @Test void textLimitIsAVisibleFailure() {
        byte[] bytes = "x".repeat(BoundedDocumentParser.MAX_TEXT_CHARS + 1).getBytes(StandardCharsets.UTF_8);
        var result = parser.parse(1L, 0, "oversize.txt", new ByteArrayInputStream(bytes));
        assertThat(result.status()).isEqualTo(ParseStatus.LIMIT_EXCEEDED);
        assertThat(result.chunks()).isEmpty();
    }

    @Test void lossyAndLosslessWebpDecodeBeforeVisionProcessing() throws Exception {
        for (String name : java.util.List.of("sample-lossy.webp", "sample-lossless.webp")) {
            var result = parser.parse(7L, 3L, name, resource(name));
            assertThat(result.status()).isEqualTo(ParseStatus.SUCCESS);
            assertThat(result.chunks()).singleElement()
                    .extracting(c -> c.sourceKind()).isEqualTo("ORIGINAL_IMAGE");
        }
    }

    @Test void truncatedWebpIsCorruptedEvenWhenItsHeaderIdentifiesTheImage() throws Exception {
        byte[] full;
        try (InputStream input = resource("sample-lossy.webp")) { full = input.readAllBytes(); }
        var result = parser.parse(7L, 3L, "truncated.webp",
                new ByteArrayInputStream(Arrays.copyOf(full, 34)));
        assertThat(result.status()).isEqualTo(ParseStatus.CORRUPTED);
        assertThat(result.chunks()).isEmpty();
    }

    @Test void docxCumulativeExpansionIsRejectedBeforeWordLoadsIt() throws Exception {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] block = new byte[64 * 1024];
        try (ZipOutputStream zip = new ZipOutputStream(compressed)) {
            for (int entry = 0; entry < 5; entry++) {
                zip.putNextEntry(new ZipEntry("word/media/large-" + entry));
                for (int repeat = 0; repeat < 14 * 1024 * 1024 / block.length; repeat++) {
                    zip.write(block);
                }
                zip.closeEntry();
            }
        }
        var result = parser.parse(1L, 0, "expanded.docx",
                new ByteArrayInputStream(compressed.toByteArray()));
        assertThat(result.status()).isEqualTo(ParseStatus.LIMIT_EXCEEDED);
        assertThat(result.chunks()).isEmpty();
    }

    private InputStream resource(String name) {
        return getClass().getClassLoader().getResourceAsStream("test-files/" + name);
    }
}
