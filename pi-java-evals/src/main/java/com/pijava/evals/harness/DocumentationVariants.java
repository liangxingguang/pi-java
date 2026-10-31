package com.pijava.evals.harness;

/**
 * 批 32（docs/44 §3.4）：文档变体的提示变换与校验 —— pi
 * {@code excludePiDocumentation}/{@code verifySystemPrompt}
 * （{@code harness.ts:494-506}/{@code :257-270}）对位。
 *
 * <p>判定只认段标签（{@code <docs>}/{@code </docs>}/{@code <rules>}/{@code <cwd>}）；
 * 正文首行是 pi-java 的实际文本（`Pi-java documentation ...`），不逐字抄 pi。</p>
 */
public final class DocumentationVariants {

    /** Docs section start marker. */
    public static final String DOCS_START = "\n<docs>\n";

    /** Docs section end marker. */
    public static final String DOCS_END = "\n</docs>";

    /** Working-directory section marker. */
    public static final String CWD_START = "\n<cwd>\n";

    /** Rules section marker. */
    public static final String RULES_START = "\n<rules>\n";

    private DocumentationVariants() {
    }

    /**
     * Remove the Pi documentation section from a rendered system prompt.
     *
     * @param defaultPrompt rendered prompt
     * @return prompt without the docs section
     * @throws IllegalStateException if the prompt has no complete docs section
     */
    public static String excludePiDocumentation(String defaultPrompt) {
        var start = defaultPrompt.indexOf(DOCS_START);
        if (start == -1) {
            throw new IllegalStateException(
                "Default Pi system prompt has no Pi documentation section.");
        }
        var end = defaultPrompt.indexOf(DOCS_END, start);
        if (end == -1) {
            throw new IllegalStateException(
                "Default Pi system prompt has no complete Pi documentation section.");
        }
        var cwdStart = defaultPrompt.lastIndexOf(CWD_START);
        if (cwdStart < end) {
            throw new IllegalStateException(
                "Default Pi system prompt has no working-directory section.");
        }
        return defaultPrompt.substring(0, start)
            + defaultPrompt.substring(end + DOCS_END.length());
    }

    /**
     * Remove the docs section when present, otherwise keep the prompt unchanged.
     *
     * <p>批 32（docs/44 §7.1）：{@code without-docs} 容器镜像已物理删除 docs，渲染后
     * 的提示本就没有 {@code <docs>} 段；此时保持原样，避免把「目标状态已达成」误报
     * 为变换失败。宿主模式（docs 存在）行为与 {@link #excludePiDocumentation} 相同。</p>
     *
     * @param defaultPrompt rendered prompt
     * @return prompt without a docs section, or unchanged when none exists
     * @throws IllegalStateException if a docs section is present but incomplete
     */
    public static String excludePiDocumentationIfPresent(String defaultPrompt) {
        if (!defaultPrompt.contains(DOCS_START)) {
            return defaultPrompt;
        }
        return excludePiDocumentation(defaultPrompt);
    }

    /**
     * Verify the variant's expected documentation presence.
     *
     * @param systemPrompt rendered prompt
     * @param name         variant name for the error message
     * @param expected     {@code null} ⇒ no check; else expected presence
     * @return the prompt unchanged
     * @throws IllegalStateException if rules are missing or docs presence differs
     */
    public static String verifySystemPrompt(String systemPrompt, String name,
                                            Boolean expected) {
        if (expected == null) {
            return systemPrompt;
        }
        if (!systemPrompt.contains(RULES_START)) {
            throw new IllegalStateException(
                "Pi system prompt lost its rules in the " + name + " eval variant.");
        }
        var hasDocumentation = systemPrompt.contains(DOCS_START);
        if (hasDocumentation != expected) {
            throw new IllegalStateException(
                "Pi system prompt does not match the " + name + " eval variant.");
        }
        return systemPrompt;
    }
}
