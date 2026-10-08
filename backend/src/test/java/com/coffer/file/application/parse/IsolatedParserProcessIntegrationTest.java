package com.coffer.file.application.parse;

import com.coffer.file.domain.parse.ParseStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in test against a freshly packaged jar; exercises the parent/worker protocol. */
class IsolatedParserProcessIntegrationTest {
    @Test
    void sourceReadCannotOutliveTheWallClockLimit() {
        CountDownLatch released = new CountDownLatch(1);
        InputStream blocked = new InputStream() {
            @Override
            public int read() {
                for (;;) {
                    try {
                        released.await();
                        return -1;
                    } catch (InterruptedException ignored) {
                        // A storage stream is not required to respond to interruption.
                    }
                }
            }

            @Override
            public void close() throws IOException {
                released.countDown();
            }
        };
        try {
            long started = System.nanoTime();
            var result = IsolatedParserProcess.parse(42L, 7L, "sample.txt", blocked, 150);
            assertThat(result.status()).isEqualTo(ParseStatus.LIMIT_EXCEEDED);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        } finally {
            released.countDown();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "COFFER_PARSER_JAR", matches = ".+")
    void packagedWorkerParsesInASeparateJvm() {
        String original = System.getProperty("java.class.path");
        try {
            System.setProperty("java.class.path", System.getenv("COFFER_PARSER_JAR"));
            var document = IsolatedParserProcess.parse(42L, 7L, "sample.txt",
                    new ByteArrayInputStream("first line\nsecond line".getBytes(StandardCharsets.UTF_8)));
            assertThat(document.status()).isEqualTo(ParseStatus.SUCCESS);
            assertThat(document.fileId()).isEqualTo(42L);
            assertThat(document.revision()).isEqualTo(7L);
            assertThat(document.chunks()).extracting(c -> c.sourceKind())
                    .containsExactly("LINE", "LINE");
        } finally {
            if (original == null) System.clearProperty("java.class.path");
            else System.setProperty("java.class.path", original);
        }
    }
}
