package com.coffer.file.api.support;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class TextPreviewEncodingTest {
    @Test void sampleBoundaryInsideChineseCharacterDoesNotMisdetectUtf8OrLoseBytes() throws Exception {
        String text = "a".repeat(65535) + "中文预览\n";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        var prepared = TextPreviewEncoding.prepare(new ByteArrayInputStream(bytes));
        try (var stream = prepared.stream()) {
            assertThat(prepared.charset()).isEqualTo(StandardCharsets.UTF_8);
            assertThat(stream.readAllBytes()).isEqualTo(bytes);
        }
    }
}
