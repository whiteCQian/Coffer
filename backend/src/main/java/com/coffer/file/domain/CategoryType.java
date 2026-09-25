package com.coffer.file.domain;

/**
 * 文件分类枚举（受控词表）。
 *
 * <p>LLM 打标时单独输出一个受控分类（而非从多标签里挑），模型只允许取以下枚举值；
 * 未命中时经 {@link #fromLabel(String)} 兜底为 {@link #OTHER}，保证归档目录结构闭合不爆炸。
 *
 * <p>每个常量含：{@code label}（中文分类标签，送 LLM 的合法值）与
 * {@code slug}（归档目录名，见分类目录归档任务清单 T0.3——映射写在枚举，不引 yml/DB）。
 * 值以字符串持久化（{@code @Enumerated(EnumType.STRING)}，在 {@code FileMetadata.category} 上标注），
 * 修改枚举名会影响存量数据，生产库需同步迁移存量值。
 */
public enum CategoryType {

    /** 合同。 */
    CONTRACT("合同", "contracts"),

    /** 发票。 */
    INVOICE("发票", "invoices"),

    /** 报告。 */
    REPORT("报告", "reports"),

    /** 证件。 */
    ID("证件", "ids"),

    /** 图片。 */
    IMAGE("图片", "images"),

    /** 视频。 */
    VIDEO("视频", "videos"),

    /** 其他（兜底，未命中任何分类时归档到 uncategorized）。 */
    OTHER("其他", "uncategorized");

    /** 中文分类标签（送 LLM 的合法值）。 */
    private final String label;

    /** 归档目录名（slug）。 */
    private final String slug;

    CategoryType(String label, String slug) {
        this.label = label;
        this.slug = slug;
    }

    /**
     * 获取中文分类标签。
     *
     * @return 如 "合同"
     */
    public String getLabel() {
        return label;
    }

    /**
     * 获取归档目录名（slug）。
     *
     * @return 如 "contracts"
     */
    public String getSlug() {
        return slug;
    }

    /**
     * 根据字符串值解析分类枚举：忽略首尾空白与大小写，优先匹配中文 {@link #label}，
     * 其次匹配枚举 {@code name}；无法匹配或为空时一律返回 {@link #OTHER}（容错兜底，不抛异常）。
     *
     * @param value 分类字符串（如 "合同"、"CONTRACT"、" 发票 "）
     * @return 匹配的枚举常量；无法匹配或为空时返回 {@link #OTHER}
     */
    public static CategoryType fromLabel(String value) {
        if (value == null || value.isBlank()) {
            return OTHER;
        }
        String normalized = value.trim();
        for (CategoryType category : values()) {
            if (category.label.equals(normalized) || category.name().equalsIgnoreCase(normalized)) {
                return category;
            }
        }
        return OTHER;
    }
}
