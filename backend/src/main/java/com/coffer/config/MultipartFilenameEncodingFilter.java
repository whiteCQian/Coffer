package com.coffer.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * multipart 文件名编码过滤器：修复中文文件名乱码。
 *
 * <p>背景：Spring Boot 的 {@code CharacterEncodingFilter} 会把请求字符集设为 UTF-8，
 * Tomcat 懒解析 multipart 时按该字符集解码 {@code Content-Disposition} 头中的文件名。
 * 前端正常发送 UTF-8 字节没有问题；但 Windows 下部分客户端（Git Bash/PowerShell 的 curl、
 * 老工具）按本地编码发送 GBK 字节，被按 UTF-8 解码会产生 U+FFFD 替换符，原始字节
 * 不可逆丢失（任何字符串后处理都无法还原）。
 *
 * <p>解决：对 multipart 请求把请求字符集覆盖为 ISO-8859-1——Tomcat 将逐字节保留
 * multipart 头中的文件名（无损），再由 {@link com.coffer.file.api.support.FilenameEncodingFixer}
 * 在接收侧按 UTF-8 / GBK 重新解释还原。仅影响 multipart 请求的文件名解码：
 * <ul>
 *   <li>JSON 请求体（chat/标签确认等）由 Jackson 自行按 UTF-8 解析，不受影响；</li>
 *   <li>GET 查询参数走 URI 编码（默认 UTF-8），不受影响；</li>
 *   <li>multipart 中的文件内容按原始字节读取，不受影响。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // 在 CharacterEncodingFilter 之后执行，覆盖其 UTF-8 设置
public class MultipartFilenameEncodingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith("multipart/")) {
            // 覆盖为 ISO-8859-1：multipart 头字节无损保留，供 FilenameEncodingFixer 还原中文文件名
            request.setCharacterEncoding(StandardCharsets.ISO_8859_1.name());
        }
        filterChain.doFilter(request, response);
    }
}
