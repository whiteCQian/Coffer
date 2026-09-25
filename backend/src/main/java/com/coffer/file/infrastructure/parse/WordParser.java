package com.coffer.file.infrastructure.parse;

import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Word 解析器，基于 Apache POI 提取全文文本。
 *
 * <p>优先按 .docx（OOXML）解析，失败时回退到旧版 .doc（OLE2/HWPF）。
 * 输入流先读入字节数组，以便 docx/doc 两条路径各自使用独立的流，避免流位置错乱。
 */
@Component
public class WordParser implements DocumentParser {

    /** Word 解析失败时的错误提示。 */
    private static final String ERROR_PARSE = "Word 文件解析失败";
    /** 提取文本为空时的兜底文案。 */
    private static final String EMPTY_TEXT = "（文档内容为空）";

    @Override
    public String parseToString(InputStream inputStream) {
        if (inputStream == null) {
            throw new IllegalArgumentException("输入流不能为空");
        }
        byte[] data;
        try (InputStream in = inputStream) {
            data = in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException(ERROR_PARSE, e);
        }

        try {
            return parseDocx(new ByteArrayInputStream(data));
        } catch (Exception docxEx) {
            // .docx 失败则回退到旧版 .doc
            try {
                return parseDoc(new ByteArrayInputStream(data));
            } catch (Exception docEx) {
                throw new RuntimeException(ERROR_PARSE, docEx);
            }
        }
    }

    @Override
    public String getFileExtension() {
        return "docx";
    }

    /**
     * .docx（OOXML）解析。
     */
    private String parseDocx(InputStream in) throws IOException {
        try (XWPFDocument document = new XWPFDocument(in);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return normalize(extractor.getText());
        }
    }

    /**
     * 旧版 .doc（OLE2/HWPF）解析。
     */
    private String parseDoc(InputStream in) throws IOException {
        try (POIFSFileSystem fs = new POIFSFileSystem(in);
             WordExtractor extractor = new WordExtractor(fs)) {
            return normalize(extractor.getText());
        }
    }

    /**
     * 去除首尾空白，空文本返回兜底文案。
     */
    private String normalize(String text) {
        if (text == null || text.trim().isEmpty()) {
            return EMPTY_TEXT;
        }
        return text.trim();
    }
}
