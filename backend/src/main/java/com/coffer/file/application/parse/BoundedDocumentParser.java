package com.coffer.file.application.parse;

import com.coffer.file.domain.parse.ParseStatus;
import com.coffer.file.domain.parse.ParsedDocument;
import com.coffer.file.domain.parse.ParsedDocument.Chunk;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipInputStream;

/** One bounded, signature-checked parse path for uploads, inbox imports, tools and reindexing. */
@Component
public class BoundedDocumentParser {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    public static final int MAX_TEXT_CHARS = 1_000_000;
    public static final int MAX_PAGES = 200;
    public static final long MAX_PIXELS = 16_000_000L;
    private static final long MAX_ZIP_ENTRY_BYTES = 16L * 1024 * 1024;
    private static final long MAX_ZIP_EXPANDED_BYTES = 64L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 2_000;
    private static final int MAX_CHUNKS = 20_000;
    private static final long MAX_NANOS = Duration.ofSeconds(15).toNanos();
    public static final String VERSION = "bounded-2";
    @Value("${coffer.parser.process-isolation:true}")
    private boolean processIsolation;

    static {
        // POI also checks compression ratio. Cap each expanded entry and ZIP fan-out.
        org.apache.poi.openxml4j.util.ZipSecureFile.setMaxEntrySize(MAX_ZIP_ENTRY_BYTES);
        org.apache.poi.openxml4j.util.ZipSecureFile.setMaxFileCount(MAX_ZIP_ENTRIES);
        org.apache.poi.openxml4j.util.ZipSecureFile.setMaxTextSize(MAX_TEXT_CHARS);
    }

    public ParsedDocument parse(Long fileId, long revision, String fileName, InputStream input) {
        if (processIsolation) return IsolatedParserProcess.parse(fileId, revision, fileName, input);
        String ext = extension(fileName);
        if (!List.of("txt", "pdf", "doc", "docx", "jpg", "jpeg", "png", "gif", "bmp", "webp").contains(ext))
            return failure(fileId, revision, ext, ParseStatus.UNSUPPORTED);
        if (input == null) return failure(fileId, revision, ext, ParseStatus.CORRUPTED);
        try {
            long deadline = System.nanoTime() + MAX_NANOS;
            byte[] data = boundedRead(input, deadline);
            if (data.length > MAX_BYTES) return failure(fileId, revision, ext, ParseStatus.LIMIT_EXCEEDED);
            if (!signatureMatches(ext, data)) return failure(fileId, revision, ext, ParseStatus.TYPE_MISMATCH);
            List<Chunk> chunks = switch (ext) {
                case "txt" -> parseText(data, deadline);
                case "pdf" -> parsePdf(data, deadline);
                case "docx" -> parseDocx(data, deadline);
                case "doc" -> parseDoc(data, deadline);
                default -> image(data, deadline);
            };
            ParseStatus status = chunks.isEmpty() ? ParseStatus.EMPTY_CONTENT : ParseStatus.SUCCESS;
            return new ParsedDocument(fileId, revision, ext, VERSION, status, null, chunks);
        } catch (InvalidPasswordException encrypted) {
            return failure(fileId, revision, ext, ParseStatus.ENCRYPTED);
        } catch (LimitException limit) {
            return failure(fileId, revision, ext, ParseStatus.LIMIT_EXCEEDED);
        } catch (Exception corrupt) {
            return failure(fileId, revision, ext, ParseStatus.CORRUPTED);
        }
    }

    private List<Chunk> parseText(byte[] data, long deadline) throws CharacterCodingException {
        if (data.length == 0) return List.of();
        String value;
        try {
            value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            // Legacy TXT imports may be GBK, but binary/control-heavy data must never be treated as text.
            value = java.nio.charset.Charset.forName("GBK").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        }
        if (value.indexOf('\0') >= 0 || value.chars().filter(c -> c < 32 && c != '\n' && c != '\r' && c != '\t').count() > 0)
            throw new IllegalArgumentException("binary text");
        if (value.length() > MAX_TEXT_CHARS) throw new LimitException();
        List<Chunk> chunks = new ArrayList<>();
        int line = 1, offset = 0;
        for (String part : value.split("\\R", -1)) {
            check(deadline, chunks.size());
            int actualStart = value.indexOf(part, offset);
            if (actualStart < 0) actualStart = offset;
            if (!part.isEmpty()) chunks.add(new Chunk(part, "LINE", line, line, actualStart + 1, actualStart + part.length()));
            offset = actualStart + part.length();
            if (offset < value.length() && value.charAt(offset) == '\r') offset++;
            if (offset < value.length() && value.charAt(offset) == '\n') offset++;
            line++;
        }
        return chunks;
    }

    private List<Chunk> parsePdf(byte[] data, long deadline) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(data)) {
            if (pdf.getNumberOfPages() > MAX_PAGES) throw new LimitException();
            PDFTextStripper stripper = new PDFTextStripper();
            List<Chunk> chunks = new ArrayList<>();
            int characters = 0;
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                check(deadline, chunks.size());
                stripper.setStartPage(page); stripper.setEndPage(page);
                String text = stripper.getText(pdf).trim();
                characters += text.length();
                if (characters > MAX_TEXT_CHARS) throw new LimitException();
                if (!text.isEmpty()) chunks.add(new Chunk(text, "PAGE", page, page, 1, text.length()));
            }
            return chunks;
        }
    }

    private List<Chunk> parseDocx(byte[] data, long deadline) throws IOException {
        validateOfficeZip(data, deadline);
        try (XWPFDocument word = new XWPFDocument(new ByteArrayInputStream(data))) {
            List<Chunk> chunks = new ArrayList<>();
            int number = 0, characters = 0;
            for (var body : word.getBodyElements()) {
                check(deadline, chunks.size());
                if (body instanceof org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph) {
                    number++;
                    String value = paragraph.getText();
                    if (value == null || value.isBlank()) continue;
                    characters += value.length();
                    if (characters > MAX_TEXT_CHARS) throw new LimitException();
                    chunks.add(new Chunk(value, "PARAGRAPH", number, number, 1, value.length()));
                } else if (body instanceof org.apache.poi.xwpf.usermodel.XWPFTable table) {
                    for (var row : table.getRows()) for (var cell : row.getTableCells()) {
                        number++;
                        String value = cell.getText();
                        if (value == null || value.isBlank()) continue;
                        characters += value.length();
                        if (characters > MAX_TEXT_CHARS) throw new LimitException();
                        check(deadline, chunks.size());
                        chunks.add(new Chunk(value, "PARAGRAPH", number, number, 1, value.length()));
                    }
                }
            }
            return chunks;
        }
    }

    /** Reject cumulative ZIP expansion before POI constructs an in-memory Word document. */
    private void validateOfficeZip(byte[] data, long deadline) throws IOException {
        long expanded = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) throw new LimitException();
                long entryBytes = 0;
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    check(deadline, 0);
                    if (count > MAX_ZIP_ENTRY_BYTES - entryBytes
                            || count > MAX_ZIP_EXPANDED_BYTES - expanded) throw new LimitException();
                    entryBytes += count;
                    expanded += count;
                }
                zip.closeEntry();
            }
        }
        if (entries == 0) throw new IOException("Empty Office ZIP");
    }

    private List<Chunk> parseDoc(byte[] data, long deadline) throws IOException {
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(data));
             WordExtractor word = new WordExtractor(fs)) {
            List<Chunk> chunks = new ArrayList<>();
            int characters = 0, number = 0;
            for (String value : word.getParagraphText()) {
                check(deadline, chunks.size());
                number++;
                if (value == null || value.isBlank()) continue;
                characters += value.length();
                if (characters > MAX_TEXT_CHARS) throw new LimitException();
                chunks.add(new Chunk(value, "PARAGRAPH", number, number, 1, value.length()));
            }
            return chunks;
        }
    }

    private List<Chunk> image(byte[] data, long deadline) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (stream == null) throw new IOException("Image stream unavailable");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Unsupported or damaged image");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) throw new LimitException();
                // Header-only reads accept truncated JPEG/PNG/WebP bodies. Decode once under
                // the pixel bound before an image can enter the vision-model path.
                if (reader.read(0) == null) throw new IOException("Image decode returned no pixels");
                check(deadline, 0);
            } finally { reader.dispose(); }
        }
        return List.of(new Chunk("", "ORIGINAL_IMAGE", 1, 1, 0, 0));
    }
    private static void check(long deadline, int chunks) {
        if (System.nanoTime() > deadline || chunks >= MAX_CHUNKS) throw new LimitException();
    }
    private static byte[] boundedRead(InputStream input, long deadline) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer, 0, Math.min(buffer.length, MAX_BYTES + 1 - out.size()))) != -1) {
            check(deadline, 0);
            out.write(buffer, 0, count);
            if (out.size() > MAX_BYTES) break;
        }
        return out.toByteArray();
    }
    private static String extension(String name) {
        if (name == null || !name.contains(".")) return "";
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
    private static boolean signatureMatches(String ext, byte[] b) {
        if ("txt".equals(ext)) return true;
        if (b.length < 8) return false;
        return switch (ext) {
            case "pdf" -> b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-';
            case "docx" -> b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4;
            case "doc" -> b[0] == (byte) 0xD0 && b[1] == (byte) 0xCF && b[2] == 0x11 && b[3] == (byte) 0xE0;
            case "png" -> b[0] == (byte) 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
            case "jpg", "jpeg" -> b[0] == (byte) 0xFF && b[1] == (byte) 0xD8 && b[2] == (byte) 0xFF;
            case "gif" -> b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8';
            case "bmp" -> b[0] == 'B' && b[1] == 'M';
            case "webp" -> b.length >= 16 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
            default -> false;
        };
    }
    private static ParsedDocument failure(Long id, long revision, String ext, ParseStatus code) {
        return new ParsedDocument(id, revision, ext, VERSION, code, code.name(), List.of());
    }
    private static final class LimitException extends RuntimeException { }
}
