package com.coffer.file.api.support;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

/**
 * 上传文件名的编码还原工具。
 *
 * <p>背景：multipart 请求头中的 {@code filename} 在 Tomcat 侧按 ISO-8859-1 解码。
 * 前端（浏览器/axios）发送 UTF-8 字节时由 Spring 正确还原；但部分 Windows 客户端
 * （Git Bash curl、老工具）按系统本地编码发送 GBK 字节，后端会把 GBK 字节当作
 * Latin-1 字符存储，产生乱码（如 {@code 冒烟合同.txt} 变成 Latin-1 形如的 mojibake）。
 *
 * <p>还原策略（只处理「全单字节」串，绝不误伤正常 UTF-8 中文名）：
 * <ol>
 *   <li>含 {@code > 0xFF} 字符 → 已是正确多字节文本（UTF-8 客户端还原后的名称），原样返回；</li>
 *   <li>全单字节 → 可无损映射回原始字节，优先按 UTF-8 解释（客户端发了 UTF-8 但被
 *       ISO-8859-1 解码的场景），含非法字节时再尝试 GBK（Windows 客户端常见）。</li>
 * </ol>
 */
public final class FilenameEncodingFixer {

    /** U+FFFD 替换符：UTF-8 解码遇到非法字节序列时产生。 */
    private static final String REPLACEMENT_CHAR = "�";

    private FilenameEncodingFixer() {
    }

    /**
     * 还原乱码文件名。输入不含乱码或无法可靠还原时，返回原值。
     *
     * @param original 后端收到的原始文件名
     * @return 还原后的文件名（或原值）
     */
    public static String fix(String original) {
        if (original == null || original.isBlank()) {
            return original;
        }
        // 含 >0xFF 字符说明已是正确多字节文本（如 UTF-8 客户端还原后的中文名），无需修复
        if (original.chars().anyMatch(c -> c > 0xFF)) {
            return original;
        }
        // 每个 char ∈ [0x00, 0xFF]，可无损映射回 multipart 头中的原始字节
        byte[] raw = original.getBytes(StandardCharsets.ISO_8859_1);
        // 优先按 UTF-8 解释：合法 UTF-8 序列还原成功（无替换符）
        String utf8 = new String(raw, StandardCharsets.UTF_8);
        if (!utf8.contains(REPLACEMENT_CHAR)) {
            return utf8;
        }
        // UTF-8 解释失败（含非法字节序列）→ 尝试 GBK，常见于 Windows 本地编码客户端
        try {
            String gbk = new String(raw, Charset.forName("GBK"));
            if (!gbk.contains(REPLACEMENT_CHAR)) {
                return gbk;
            }
        } catch (UnsupportedCharsetException ignore) {
            // 平台缺少 GBK 时保持原值
        }
        return original;
    }
}
