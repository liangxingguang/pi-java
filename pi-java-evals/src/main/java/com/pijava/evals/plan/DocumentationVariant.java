package com.pijava.evals.plan;

/**
 * 批 32（docs/44 §5.1）：docs-lift 对照的两个变体 —— pi
 * {@code "without_docs"}/{@code "with_docs"} 字面对位。
 */
public enum DocumentationVariant {

    /** 系统提示裁掉 Pi 文档段。 */
    WITHOUT_DOCS("without_docs"),

    /** 系统提示保留 Pi 文档段（默认）。 */
    WITH_DOCS("with_docs");

    private final String wireName;

    DocumentationVariant(String wireName) {
        this.wireName = wireName;
    }

    /**
     * Wire literal used in arm ids and observations.
     *
     * @return {@code without_docs} or {@code with_docs}
     */
    public String wireName() {
        return wireName;
    }

    /**
     * Parse a wire literal.
     *
     * @param value wire literal
     * @return the matching variant
     * @throws IllegalArgumentException if the literal is unknown
     */
    public static DocumentationVariant fromWire(String value) {
        for (var variant : values()) {
            if (variant.wireName.equals(value)) {
                return variant;
            }
        }
        throw new IllegalArgumentException("Unknown documentation variant: " + value);
    }
}
