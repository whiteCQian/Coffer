package com.coffer.tag.domain;

/**
 * 标签确认状态枚举，用于实现「人在回路」的人工确认功能。
 *
 * <p>AI 自动生成的标签先以 {@link #PENDING_CONFIRMATION} 状态挂到文件上，
 * 用户确认后置为 {@link #CONFIRMED}，拒绝则置为 {@link #REJECTED} 并填写修正意见。
 *
 * <p>注意：值以字符串持久化（{@code @Enumerated(EnumType.STRING)}），
 * 修改枚举名会影响存量数据，生产库需同步迁移存量值。
 */
public enum ConfirmationStatus {

    /** 待确认：AI 已生成，等待用户确认。 */
    PENDING_CONFIRMATION("待确认"),

    /** 已确认：用户认可该标签。 */
    CONFIRMED("已确认"),

    /** 已拒绝：用户否定了该标签（可附修正意见）。 */
    REJECTED("已拒绝");

    /** 状态描述。 */
    private final String description;

    ConfirmationStatus(String description) {
        this.description = description;
    }

    /**
     * 获取状态描述。
     *
     * @return 描述文案（如"待确认"）
     */
    public String getDescription() {
        return description;
    }

    /**
     * 根据字符串值解析枚举常量，忽略大小写与首尾空白。
     *
     * @param value 枚举名称字符串（如 "pending_confirmation"、"CONFIRMED"）
     * @return 匹配的枚举常量；{@code value} 为空返回 {@code null}
     * @throws IllegalArgumentException 无法匹配时抛出
     */
    public static ConfirmationStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        for (ConfirmationStatus status : values()) {
            if (status.name().equalsIgnoreCase(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的标签确认状态: " + value);
    }
}
