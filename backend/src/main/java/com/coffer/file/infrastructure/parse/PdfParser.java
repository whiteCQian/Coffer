package com.coffer.file.infrastructure.parse;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * PDF 解析器，基于 Apache PDFBox 提取全文文本。
 *
 * <p>PDFBox 3.x 已移除 {@code PDDocument.load(InputStream)}，改用 {@link Loader#loadPDF(byte[])}，
 * 故先将输入流读入字节数组再加载。
 */
@Component
public class PdfParser implements DocumentParser {

    /** PDF 加密且未提供密码时的错误提示。 */
    private static final String ERROR_ENCRYPTED = "PDF 文件已加密，无法解析";
    /** PDF 解析失败时的错误提示。 */
    private static final String ERROR_PARSE = "PDF 文件解析失败";
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

        try (PDDocument document = Loader.loadPDF(data)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            return normalize(text);
        } catch (InvalidPasswordException e) {
            // InvalidPasswordException 是 IOException 子类，须先于 IOException 捕获
            throw new RuntimeException(ERROR_ENCRYPTED, e);
        } catch (IOException e) {
            throw new RuntimeException(ERROR_PARSE, e);
        }
    }

    @Override
    public String getFileExtension() {
        return "pdf";
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
