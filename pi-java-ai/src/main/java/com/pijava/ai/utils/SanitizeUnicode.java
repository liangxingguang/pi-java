package com.pijava.ai.utils;

/**
 * 孤对代理字符净化 —— pi {@code packages/ai/src/utils/sanitize-unicode.ts} 的移植
 * （package A0，{@code docs/43}）。
 *
 * <p>pi 的 JSDoc 逐字写明动机：孤对代理（{@code 0xD800-0xDBFF} 无配对低代理，
 * 或反之）<em>"cause JSON serialization errors in many API providers"</em>。
 * 合法 emoji 等 BMP 外字符用的是<b>成对</b>代理，不受影响。</p>
 *
 * <p><b>实现口径（D3）</b>：按 UTF-16 code unit 走（孤对代理<b>没有码点</b>，
 * {@code codePointAt} 不适用）—— 高代理紧跟低代理 ⇒ 两者保留并跳 2；否则遇代理
 * 即删。与 pi 的正则语义严格等价，等价性由 {@code SanitizeUnicodeTest} 以 pi
 * 正则作 oracle 做双向差分证明。</p>
 *
 * <p>无变化时返回原实例（pi 的 {@code String.replace} 在无匹配时同样返回原串）。</p>
 */
public final class SanitizeUnicode {

    private SanitizeUnicode() {}

    /**
     * pi {@code sanitizeSurrogates(text)}（sanitize-unicode.ts:21）：删掉未配对的
     * 代理字符，保留成对代理。
     *
     * <p>{@code null} 不处理（与 pi 的 {@code undefined.replace} 抛错同构，自然 NPE）。</p>
     */
    public static String surrogates(String text) {
        int length = text.length();
        StringBuilder out = null; // 惰性分配：无孤对时不复制
        int last = 0;             // 上一段已确认干净文本的起点
        int i = 0;
        while (i < length) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
                i += 2; // 成对 ⇒ 保留，整体跳过
                continue;
            }
            if (Character.isHighSurrogate(c) || Character.isLowSurrogate(c)) {
                // 孤对 ⇒ 删除：先把前面干净的段落刷出去，再跳过这一个 code unit
                if (out == null) {
                    out = new StringBuilder(length);
                }
                out.append(text, last, i);
                i++;
                last = i;
                continue;
            }
            i++;
        }
        if (out == null) {
            return text;
        }
        out.append(text, last, length);
        return out.toString();
    }
}