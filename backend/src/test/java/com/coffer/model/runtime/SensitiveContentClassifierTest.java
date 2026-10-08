package com.coffer.model.runtime;

import com.coffer.file.domain.FileMetadata;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SensitiveContentClassifierTest {
    private final SensitiveContentClassifier rules = new SensitiveContentClassifier();

    @Test void findsSensitiveFilenameAndLocalSecrets() {
        assertThat(rules.classify(file("工资单.txt"), "ordinary", true))
                .isEqualTo(SensitiveContentClassifier.Risk.SENSITIVE);
        assertThat(rules.classify(file("note.txt"), "api_key = sk-1234567890abcdef", true))
                .isEqualTo(SensitiveContentClassifier.Risk.SENSITIVE);
        assertThat(rules.classify(file("note.txt"), "-----BEGIN PRIVATE KEY-----", true))
                .isEqualTo(SensitiveContentClassifier.Risk.SENSITIVE);
        assertThat(rules.classify(file("note.txt"), "本月工资合计 50000 元", true))
                .isEqualTo(SensitiveContentClassifier.Risk.SENSITIVE);
        assertThat(rules.classify(file("note.txt"), "护照号码：E12345678", true))
                .isEqualTo(SensitiveContentClassifier.Risk.SENSITIVE);
    }

    @Test void imagesAndFailedParsesStayUnknown() {
        assertThat(rules.classify(file("photo.png"), null, false))
                .isEqualTo(SensitiveContentClassifier.Risk.UNKNOWN);
        assertThat(rules.classify(file("notes.txt"), "meeting agenda", true))
                .isEqualTo(SensitiveContentClassifier.Risk.CLEAR);
    }

    private static FileMetadata file(String name) { return FileMetadata.builder().fileName(name).build(); }
}
