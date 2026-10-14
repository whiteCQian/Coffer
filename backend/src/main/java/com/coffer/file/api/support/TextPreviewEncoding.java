package com.coffer.file.api.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Detects common TXT encodings without changing bytes or buffering the whole file. */
public final class TextPreviewEncoding {
    private static final int SAMPLE_BYTES = 64 * 1024;
    private TextPreviewEncoding() { }

    public record Prepared(InputStream stream, Charset charset) { }

    public static Prepared prepare(InputStream source) throws IOException {
        var stream = new PushbackInputStream(source, SAMPLE_BYTES);
        try {
            byte[] sample = stream.readNBytes(SAMPLE_BYTES);
            stream.unread(sample);
            return new Prepared(stream, detect(sample));
        } catch (IOException | RuntimeException | Error failure) {
            try { stream.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private static Charset detect(byte[] sample) {
        if (sample.length >= 2) {
            if ((sample[0] & 255) == 255 && (sample[1] & 255) == 254) return StandardCharsets.UTF_16LE;
            if ((sample[0] & 255) == 254 && (sample[1] & 255) == 255) return StandardCharsets.UTF_16BE;
        }
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        // A sample may end halfway through a valid multibyte character. Do not treat
        // that boundary as evidence of a legacy encoding when more bytes may follow.
        var result = decoder.decode(ByteBuffer.wrap(sample), CharBuffer.allocate(sample.length),
                sample.length < SAMPLE_BYTES);
        return result.isError() ? Charset.forName("GB18030") : StandardCharsets.UTF_8;
    }
}
