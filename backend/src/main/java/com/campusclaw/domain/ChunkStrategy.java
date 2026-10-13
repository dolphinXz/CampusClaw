package com.campusclaw.domain;

/** 切分策略。未指定时一律使用 AUTO（含种子/历史材料补索引）。 */
public enum ChunkStrategy {
    AUTO,
    CUSTOM,
    HIERARCHY;

    public static ChunkStrategy from(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知切分策略：" + value);
        }
    }
}
