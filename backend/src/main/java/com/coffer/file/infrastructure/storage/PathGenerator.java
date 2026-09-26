package com.coffer.file.infrastructure.storage;

import com.coffer.file.domain.CategoryType;
import com.coffer.auth.service.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 对象存储路径生成工具。
 *
 * <p>路径格式：{@code files/{yyyy}/{MM}/{dd}/{fileType}/{uuid}_{sanitizedFilename}}，
 * 按日期与类型分层目录，便于按时间归档与管理。
 */
@Slf4j
@Component
public class PathGenerator {

    /** 路径非法字符与空白（同时覆盖 Windows/Unix），统一替换为下划线以防路径注入。 */
    private static final Pattern ILLEGAL_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\s]+");

    /**
     * 生成文件存储路径。
     *
     * @param originalFilename 原始文件名
     * @param fileType         文件类型（扩展名），为空时从文件名解析，仍为空则归类到 other
     * @return 对象存储路径
     */
    public String generateStoragePath(String originalFilename, String fileType) {
        LocalDateTime now = LocalDateTime.now();
        String type = (fileType == null || fileType.isBlank())
                ? parseFileType(originalFilename)
                : fileType;
        if (type == null || type.isBlank()) {
            type = "other";
        }
        String sanitized = sanitizeFilename(originalFilename);
        String path = String.format("users/%d/files/%04d/%02d/%02d/%s/%s_%s",
                TenantContext.requireOwnerId(),
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(),
                type, UUID.randomUUID().toString(), sanitized);
        log.debug("生成存储路径");
        return path;
    }

    /**
     * 生成归档目标路径（分类目录归档）。
     *
     * <p>默认使用序号 {@code 000} 生成归档路径。实际归档路径由归档对象名分配器传入同日同名序号，
     * 格式为 {@code /{slug}/{yyyy}/{MM}/{dd}/{seq}-{sanitizedFilename}}。
     *
     * @param category         文件分类，为空时按 OTHER 处理（归档到 uncategorized）
     * @param originalFilename 原始文件名（用于提取扩展名）
     * @param uploadTime       上传时间，为空时取当前时间
     * @return 对象存储归档路径（不含前导斜杠，与上传路径约定一致）
     */
    public String generateArchivePath(CategoryType category, String originalFilename, LocalDateTime uploadTime) {
        return generateArchivePath(category, originalFilename, uploadTime, 0);
    }

    /**
     * 生成带同日同名序号的归档目标路径。
     *
     * @param sequenceNumber 同一天同名文件的三位十六进制序号，范围 0-4095
     */
    public String generateArchivePath(CategoryType category,
                                      String originalFilename,
                                      LocalDateTime uploadTime,
                                      int sequenceNumber) {
        CategoryType resolved = category == null ? CategoryType.OTHER : category;
        LocalDateTime time = uploadTime == null ? LocalDateTime.now() : uploadTime;
        if (sequenceNumber < 0 || sequenceNumber > 0xFFF) {
            throw new IllegalArgumentException("archive object sequence must be between 0 and 4095");
        }
        String fileName = String.format(Locale.ROOT, "%03X-%s", sequenceNumber,
                sanitizeArchiveFilename(originalFilename));
        String path = String.format("users/%d/archive/%s/%04d/%02d/%02d/%s",
                TenantContext.requireOwnerId(), resolved.getSlug(), time.getYear(), time.getMonthValue(), time.getDayOfMonth(), fileName);
        log.debug("生成归档路径");
        return path;
    }

    private String sanitizeArchiveFilename(String originalFilename) {
        String sanitized = sanitizeFilename(originalFilename);
        int extensionSeparator = sanitized.lastIndexOf('.');
        if (extensionSeparator > 0 && extensionSeparator < sanitized.length() - 1) {
            String baseName = sanitized.substring(0, extensionSeparator);
            String extension = sanitized.substring(extensionSeparator + 1).toLowerCase(Locale.ROOT);
            return baseName + "." + extension;
        }
        return sanitized;
    }

    /**
     * 从文件名解析扩展名（小写）。
     *
     * @param originalFilename 原始文件名
     * @return 扩展名，无扩展名时返回 null
     */
    private String parseFileType(String originalFilename) {
        if (originalFilename == null) {
            return null;
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            return null;
        }
        String ext = originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.isBlank() ? null : ext;
    }

    /**
     * 文件名安全化：去除路径非法字符与空白，防止路径注入。
     *
     * @param originalFilename 原始文件名
     * @return 安全化后的文件名
     */
    private String sanitizeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "unnamed";
        }
        return ILLEGAL_CHARS.matcher(originalFilename.trim()).replaceAll("_");
    }
}
